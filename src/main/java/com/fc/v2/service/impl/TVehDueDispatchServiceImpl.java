package com.fc.v2.service.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TVehDueHandlerMapper;
import com.fc.v2.mapper.auto.TVehDueOffdayMapper;
import com.fc.v2.mapper.auto.TVehDueTaskMapper;
import com.fc.v2.mapper.auto.TVehEntMapper;
import com.fc.v2.model.auto.TVehDueHandler;
import com.fc.v2.model.auto.TVehDueOffday;
import com.fc.v2.model.auto.TVehDueTask;
import com.fc.v2.model.auto.TVehEnt;
import com.fc.v2.model.custom.VehDueDispatchReceipt;
import com.fc.v2.service.ITVehDueDispatchService;
import com.fc.v2.util.StringUtils;

/**
 * 证照到期催办——派批次 Service业务层处理（一批一办名目）。
 *
 * 旧的 {@link TVehDueTaskServiceImpl#runOnce} 照旧只出一个数，这里不添不改；
 * 一趟只办一批的窗口、择期尺、挑桩、派人、三数回点全在本类一处定，
 * 控制器/调度/屏上问讯都只照本类这一份回话，别处不许另摆一把尺。
 *
 * @author fuce
 * @date 2026-10-05
 */
@Service
public class TVehDueDispatchServiceImpl implements ITVehDueDispatchService {

    /** 条目情形：待派（存量空着的也按待派认，新旧同尺） */
    private static final int STATUS_WAIT = 0;
    /** 条目情形：已派出 */
    private static final int STATUS_DONE = 1;
    /** 条目情形：分不出 */
    private static final int STATUS_FAIL = 2;

    /** 业户名录情形：在册 */
    private static final int ENT_ACTIVE = 0;

    /** 开口窗口：凌晨过两点起（含）、到五点前止（不含）；两头各一段静默 */
    private static final int WINDOW_FROM_HOUR = 2;
    private static final int WINDOW_TO_HOUR = 5;

    /** 一趟最多办的桩数，余下的留到来年那一趟 */
    private static final int BATCH_CAP = 500;

    /** 提前日格子里认作"大得没了边"的顶：十年的办事日都点不满 */
    private static final int MAX_AHEAD_DAYS = 3650;

    /** 分不出卡在哪一环（写回 fail_stage 一栏，日后照此捞批重排） */
    private static final String STAGE_FIELD = "校格";
    private static final String STAGE_ENT = "销户";
    private static final String STAGE_CONTACT = "无联络人";
    private static final String STAGE_ASSIGN = "派人";
    private static final String STAGE_WRITE = "回写";

    @javax.annotation.Resource
    private TVehDueTaskMapper vehDueTaskMapper;
    @javax.annotation.Resource
    private TVehEntMapper vehEntMapper;
    @javax.annotation.Resource
    private TVehDueHandlerMapper vehDueHandlerMapper;
    @javax.annotation.Resource
    private TVehDueOffdayMapper vehDueOffdayMapper;

    /**
     * 时段比较统一走墙钟字符串（yyyy-MM-dd HH:mm:ss）：与旧催办一处同一把读法，
     * 避开 JDBC serverTimezone 与本机时区错位，"恰好到期/恰好开口"这类压线才不会飘。
     * 挑桩怎么读，回写 dispatched_at 也怎么读（同一个 {@code at}）。
     */
    private static String ts(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /** 办事日的日牌子，只按日点，不凑夜里跨过去的钟点 */
    private static String dayKey(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd").format(d);
    }

    @Override
    public VehDueDispatchReceipt preview(Date at) {
        // 屏上问一声与真走一趟同源：同一段算路，只在落库那一步分道
        return build(at, false);
    }

    @Override
    public VehDueDispatchReceipt runBatch(Date at) {
        return build(at, true);
    }

    private VehDueDispatchReceipt build(Date at, boolean apply) {
        VehDueDispatchReceipt rc = new VehDueDispatchReceipt();
        if (at == null) {
            // 触发时刻都没给：应声不动，一行也不许碰
            rc.setActed(false);
            rc.setMessage("没给触发时刻，这一趟不动库");
            return rc;
        }
        rc.setMoment(ts(at));
        if (!inWindow(at)) {
            // 两点以前、五点以后两段静默：应一声这一趟不动库就完事
            rc.setInWindow(false);
            rc.setActed(false);
            rc.setMessage("此刻不在开口窗口（凌晨两点起、五点前止），这一趟不动库");
            return rc;
        }
        rc.setInWindow(true);

        Set<String> offDays = loadOffDays();
        List<TVehDueTask> all = this.vehDueTaskMapper.selectList(
                new QueryWrapper<TVehDueTask>().eq("del_flag", 0));

        // 头一遍过堂：校格撑不住的当场记；格齐的照唯一一把尺分到 开口/还欠开口
        List<TVehDueTask> eligible = new ArrayList<TVehDueTask>();
        List<BrokenRow> broken = new ArrayList<BrokenRow>();
        int waiting = 0;
        for (TVehDueTask r : all) {
            if (r.getStatus() != null && r.getStatus() != STATUS_WAIT) {
                // 上趟已派出的不许当搁着的捞回；已分不出的等人工照缘由捞批，不自动重排
                continue;
            }
            String fieldFault = fieldFault(r);
            if (fieldFault != null) {
                broken.add(new BrokenRow(r, STAGE_FIELD, fieldFault));
                continue;
            }
            if (isOpen(r, at, offDays)) {
                eligible.add(r);
            } else {
                waiting++;
            }
        }

        // 挑中的桩多过五百：止点先来先办，同一止点按代号从小到大，只办头五百
        Collections.sort(eligible, new Comparator<TVehDueTask>() {
            @Override
            public int compare(TVehDueTask a, TVehDueTask b) {
                int c = ts(a.getDueAt()).compareTo(ts(b.getDueAt()));
                if (c != 0) {
                    return c;
                }
                return a.getItemNo().compareTo(b.getItemNo());
            }
        });
        List<TVehDueTask> batch = eligible;
        if (eligible.size() > BATCH_CAP) {
            batch = new ArrayList<TVehDueTask>(eligible.subList(0, BATCH_CAP));
            List<TVehDueTask> left = eligible.subList(BATCH_CAP, eligible.size());
            waiting += left.size();
            for (TVehDueTask r : left) {
                rc.getLeftovers().add(r.getItemNo());
            }
        }
        rc.setWaitingCount(waiting);

        // 派人花名册取一份当季快照，派桩只在这份快照上排先后（最少活先接，同载按代号）
        List<HandlerLoad> handlers = loadHandlers();
        // 一家业户名下几桩不许并给同一人：记下这一趟每个户已经钉到谁，第二桩只跳过那一位
        Map<String, String> siteToHandler = new HashMap<String, String>();
        Map<String, TVehEnt> entCache = new HashMap<String, TVehEnt>();

        // 一桩一桩走完，各自的下落先记在账上；真走一趟时落库，屏上问讯只算账
        List<PlanLine> plan = new ArrayList<PlanLine>();
        for (BrokenRow b : broken) {
            plan.add(PlanLine.fail(b.row, b.stage, b.reason));
        }
        for (TVehDueTask r : batch) {
            // 两类天生分不出：业户已销户（或名录上查无）、联络人空着几季没人补——不必空跑派人
            TVehEnt ent = resolveEnt(r, entCache);
            if (ent == null) {
                plan.add(PlanLine.fail(r, STAGE_ENT,
                        StringUtils.isEmpty(r.getSiteNo())
                                ? "所对业户代号空着，总名录上查无此户"
                                : "所对业户" + r.getSiteNo() + "已从总名录销户（或查无此户）"));
                continue;
            }
            if (StringUtils.isEmpty(r.getContact()) || r.getContact().trim().isEmpty()) {
                plan.add(PlanLine.fail(r, STAGE_CONTACT, "联络人一栏空着几季没人补"));
                continue;
            }
            AssignPick pick = pickHandler(handlers, siteToHandler, ent);
            if (pick.handler == null) {
                plan.add(PlanLine.fail(r, STAGE_ASSIGN, pick.reason));
                continue;
            }
            plan.add(PlanLine.dispatch(r, pick.handler, ent.getSiteNo()));
        }

        if (apply) {
            persist(plan, at);
        }
        // 三数回库逐条点过才出口；屏上问讯就照这份账（没落库，账即当下的真相）
        settleCounts(rc, plan, at, offDays, apply);
        rc.setActed(apply);
        if (plan.isEmpty()) {
            rc.setMessage("这一趟没有到期的，寻常收口，不必当事故往上报");
        } else {
            StringBuilder msg = new StringBuilder();
            msg.append("这一趟递出去").append(rc.getDispatchedCount()).append("条，")
               .append("标成分不出").append(rc.getFailCount()).append("条，")
               .append("还欠开口").append(rc.getWaitingCount()).append("条");
            if (rc.getLeftoverCount() > 0) {
                msg.append("；挑中").append(eligible.size())
                   .append("条多过五百，按止点先办了五百，余下").append(rc.getLeftoverCount())
                   .append("条留到来年那一趟");
            }
            rc.setMessage(msg.toString());
        }
        return rc;
    }

    /** 窗口唯一判定：小时位在 [02,05) 即开口，按墙钟读，不看本机时区外的差 */
    private boolean inWindow(Date at) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(at);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        return h >= WINDOW_FROM_HOUR && h < WINDOW_TO_HOUR;
    }

    private Set<String> loadOffDays() {
        Set<String> days = new HashSet<String>();
        List<TVehDueOffday> list = this.vehDueOffdayMapper.selectList(
                new QueryWrapper<TVehDueOffday>().eq("del_flag", 0));
        for (TVehDueOffday d : list) {
            if (d.getDay() != null) {
                days.add(dayKey(d.getDay()));
            }
        }
        return days;
    }

    private List<HandlerLoad> loadHandlers() {
        List<HandlerLoad> loaded = new ArrayList<HandlerLoad>();
        List<TVehDueHandler> list = this.vehDueHandlerMapper.selectList(
                new QueryWrapper<TVehDueHandler>().eq("del_flag", 0).eq("status", 0));
        for (TVehDueHandler h : list) {
            loaded.add(new HandlerLoad(h));
        }
        Collections.sort(loaded, new Comparator<HandlerLoad>() {
            @Override
            public int compare(HandlerLoad a, HandlerLoad b) {
                int c = Integer.compare(a.load, b.load);
                if (c != 0) {
                    return c;
                }
                String an = a.h.getHandlerNo() == null ? "" : a.h.getHandlerNo();
                String bn = b.h.getHandlerNo() == null ? "" : b.h.getHandlerNo();
                return an.compareTo(bn);
            }
        });
        return loaded;
    }

    /**
     * 行上几格撑不住的当场指出来，只点头一格：代号没落、止点空着、提前日没数/带负号/没了边。
     * 格齐的返回 null。存量行与新添行同一把尺，不因录得早就另给说法。
     */
    private String fieldFault(TVehDueTask r) {
        if (StringUtils.isEmpty(r.getItemNo()) || r.getItemNo().trim().isEmpty()) {
            return "条目代号压根没落";
        }
        if (r.getDueAt() == null) {
            return "应开口的止点时刻空着";
        }
        if (r.getAmount() == null) {
            return "最可提前几日那一格没数";
        }
        if (r.getAmount().signum() < 0) {
            return "最可提前几日那一格带负号";
        }
        if (r.getAmount().intValue() > MAX_AHEAD_DAYS) {
            return "最可提前几日那一格大得没了边（顶" + MAX_AHEAD_DAYS + "日）";
        }
        return null;
    }

    /**
     * 到没到期那把尺，全系统只此一处：
     * 开口点 = 止点往前点满 amount 个办事日；办事日按日点，夜里跨过去的钟点不凑整日，
     * 国家放假、省里停办（办事日历上在册的）不凑；含开口那一刻（压线算开口），逾期照办。
     */
    private boolean isOpen(TVehDueTask r, Date at, Set<String> offDays) {
        int ahead = r.getAmount().intValue(); // 格已校过：非空、非负、没顶破天；零头不凑整日
        Calendar cal = Calendar.getInstance();
        cal.setTime(r.getDueAt());
        int stepped = 0;
        while (stepped < ahead) {
            cal.add(Calendar.DAY_OF_MONTH, -1);
            if (!offDays.contains(dayKey(cal.getTime()))) {
                stepped++;
            }
        }
        return ts(at).compareTo(ts(cal.getTime())) >= 0;
    }

    private TVehEnt resolveEnt(TVehDueTask r, Map<String, TVehEnt> cache) {
        String siteNo = r.getSiteNo();
        if (StringUtils.isEmpty(siteNo)) {
            return null;
        }
        if (cache.containsKey(siteNo)) {
            return cache.get(siteNo);
        }
        List<TVehEnt> list = this.vehEntMapper.selectList(new QueryWrapper<TVehEnt>()
                .eq("site_no", siteNo).eq("del_flag", 0));
        TVehEnt ent = null;
        if (!list.isEmpty() && list.get(0).getStatus() != null
                && list.get(0).getStatus() == ENT_ACTIVE) {
            ent = list.get(0);
        }
        cache.put(siteNo, ent);
        return ent;
    }

    /**
     * 派人先后：手上活最少的先接（同载按代号保确定）；休办、这类业户经手不来、
     * 已接过这个户另一桩的那一位，往后顺延；轮到的那位定额已塞满，本桩当场分不出，不再往后找。
     */
    private AssignPick pickHandler(List<HandlerLoad> handlers, Map<String, String> siteToHandler,
                                   TVehEnt ent) {
        String siteNo = ent.getSiteNo();
        String siteType = ent.getSiteType();
        List<String> passed = new ArrayList<String>();
        for (HandlerLoad hl : handlers) {
            TVehDueHandler h = hl.h;
            String no = h.getHandlerNo() == null ? "" : h.getHandlerNo();
            if (h.getLeaveFlag() != null && h.getLeaveFlag() == 1) {
                passed.add(no + "这一季休办");
                continue;
            }
            if (!accepts(h.getAcceptTypes(), siteType)) {
                passed.add(no + "经不来这类业户");
                continue;
            }
            String already = siteToHandler.get(siteNo);
            if (already != null && already.equals(no)) {
                // 一家业户名下几桩不许并给同一人：只跳过已接着该户的那一位，其余人照接
                passed.add(no + "名下已接着同户" + siteNo + "的桩");
                continue;
            }
            if (h.getQuota() != null && hl.load >= h.getQuota()) {
                // 轮到的这位已经塞满定额：当场分不出，缘由写明，不顺延到下一位
                return new AssignPick(null, "轮到经办人" + no + "时本季定额已塞满"
                        + h.getQuota() + "桩（环：派人）");
            }
            hl.load++;
            h.setActiveCount(hl.load);
            siteToHandler.put(siteNo, no);
            return new AssignPick(h, null);
        }
        return new AssignPick(null, "按最少活顺下来没有可接的经办人（环：派人；"
                + (passed.isEmpty() ? "花名册上没人在岗" : String.join("、", passed)) + "）");
    }

    /** 接得过来的类别册：空着=哪类都接；否则业户类别得在册子上（各自去首尾空白后原样比） */
    private boolean accepts(String acceptTypes, String siteType) {
        if (StringUtils.isEmpty(acceptTypes)) {
            return true;
        }
        String want = siteType == null ? "" : siteType.trim();
        for (String t : acceptTypes.split(",")) {
            if (t.trim().equals(want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 真走一趟才落库：一条一独立尝试，一条砸了只记它自己，不许掀翻整趟、不许提前散会。
     * 办成的一边翻词（待派→已派出）一边钉时刻+经办人，缺一不算办完；
     * 回写这一环点不动的，当场补挂"分不出（环：回写）"，补挂也点不动就留在账上等下趟。
     */
    private void persist(List<PlanLine> plan, Date at) {
        for (PlanLine line : plan) {
            TVehDueTask r = line.row;
            try {
                TVehDueTask upd = new TVehDueTask();
                upd.setId(r.getId());
                if (line.out) {
                    upd.setStatus(STATUS_DONE);
                    upd.setHandlerNo(line.handler.getHandlerNo());
                    upd.setDispatchedAt(at);
                } else {
                    upd.setStatus(STATUS_FAIL);
                    upd.setFailStage(line.stage);
                    upd.setFailReason(line.reason);
                }
                int rows = this.vehDueTaskMapper.updateById(upd);
                if (rows <= 0) {
                    throw new RuntimeException("库中没点到这一行");
                }
                if (line.out) {
                    // 手头活加一记回花名册；这一记下砸了不撤回已成的派（条目两痕已齐）
                    try {
                        TVehDueHandler hu = new TVehDueHandler();
                        hu.setId(line.handler.getId());
                        // 派出时已在当季快照上把这一桩加进去过，这里照快照记回花名册
                        hu.setActiveCount(line.handler.getActiveCount());
                        this.vehDueHandlerMapper.updateById(hu);
                    } catch (Exception ignore) {
                        // 花名册记数抖动不牵动条目，下一季对账人工补齐
                    }
                }
                line.landed = true;
            } catch (Exception e) {
                // 这一条回写砸了：当场改挂分不出（环：回写），别桩照旧一桩一桩走完
                String why = "回写库时出了岔（环：回写）：" + e.getMessage();
                line.landed = false;
                line.out = false;
                line.stage = STAGE_WRITE;
                line.reason = why;
                try {
                    TVehDueTask failUpd = new TVehDueTask();
                    failUpd.setId(r.getId());
                    failUpd.setStatus(STATUS_FAIL);
                    failUpd.setFailStage(STAGE_WRITE);
                    failUpd.setFailReason(why);
                    // 补挂若成了，库里的分不出与账上一致；不成也不再抛，留待下一趟/人工
                    line.landed = this.vehDueTaskMapper.updateById(failUpd) > 0;
                } catch (Exception ignore) {
                    line.landed = false;
                }
            }
        }
    }

    /**
     * 收口三个数：真走一趟时回库把这一趟碰过的桩逐条点一遍，屏上扫一眼的那份账与
     * 回库点的这份对不上即以回库为准（实现上同一段算路，两趟本该是同一个数）；
     * "办成"要三痕齐：词翻成已派出、派出去那一刻、经办人——缺一按没走完（分不出/环：回写）。
     * 还欠开口也回库现点（没到开口点的），再加超五百留下的——它们是到期待派，算欠办。
     */
    private void settleCounts(VehDueDispatchReceipt rc, List<PlanLine> plan, Date at,
                              Set<String> offDays, boolean apply) {
        Map<Long, TVehDueTask> dbRows = new LinkedHashMap<Long, TVehDueTask>();
        if (apply) {
            List<TVehDueTask> reloaded = this.vehDueTaskMapper.selectList(
                    new QueryWrapper<TVehDueTask>().eq("del_flag", 0));
            for (TVehDueTask t : reloaded) {
                dbRows.put(t.getId(), t);
            }
        }
        int done = 0;
        int fail = 0;
        for (PlanLine line : plan) {
            boolean complete;
            if (!apply) {
                complete = line.out;
            } else {
                TVehDueTask db = dbRows.get(line.row.getId());
                // 回库逐条点：状态、时刻、经办人三痕齐才算这一桩真办过
                complete = line.landed && db != null
                        && db.getStatus() != null && db.getStatus() == STATUS_DONE
                        && db.getDispatchedAt() != null
                        && !StringUtils.isEmpty(db.getHandlerNo());
            }
            if (complete) {
                done++;
                rc.getItems().add(new VehDueDispatchReceipt.Item(
                        line.row.getItemNo(), ts(line.row.getDueAt()), line.siteNo,
                        VehDueDispatchReceipt.OUT_DONE,
                        line.handler == null ? null : line.handler.getHandlerNo(), null, null));
            } else {
                // 回写后库里三痕没站齐（含回写环节砸了的），按分不出交账
                fail++;
                if (line.out) {
                    line.stage = STAGE_WRITE;
                    line.reason = "已派出三痕没落齐（环：回写）";
                }
                rc.getItems().add(new VehDueDispatchReceipt.Item(
                        line.row.getItemNo(),
                        line.row.getDueAt() == null ? null : ts(line.row.getDueAt()),
                        line.siteNo, VehDueDispatchReceipt.OUT_FAIL,
                        line.handler == null ? null : line.handler.getHandlerNo(),
                        line.stage, line.reason));
            }
        }
        rc.setDispatchedCount(done);
        rc.setFailCount(fail);
        rc.setLeftoverCount(rc.getLeftovers().size());
        if (apply) {
            // 还欠开口回库现点：仍是待派（含存量空状态）、格齐、尺下还没到开口点的
            int waiting = rc.getLeftovers().size();
            for (TVehDueTask t : dbRows.values()) {
                if (t.getStatus() != null && t.getStatus() != STATUS_WAIT) {
                    continue;
                }
                if (fieldFault(t) == null && !isOpen(t, at, offDays)) {
                    waiting++;
                }
            }
            rc.setWaitingCount(waiting);
        }
    }

    /** 校格就撑不住的行，当场记哪一格 */
    private static class BrokenRow {
        final TVehDueTask row;
        final String stage;
        final String reason;

        BrokenRow(TVehDueTask row, String stage, String reason) {
            this.row = row;
            this.stage = stage;
            this.reason = reason;
        }
    }

    /** 一桩在这一趟的拟下落 */
    private static class PlanLine {
        final TVehDueTask row;
        boolean out;
        TVehDueHandler handler;
        String siteNo;
        String stage;
        String reason;
        boolean landed = true;

        private PlanLine(TVehDueTask row) {
            this.row = row;
        }

        static PlanLine dispatch(TVehDueTask row, TVehDueHandler handler, String siteNo) {
            PlanLine l = new PlanLine(row);
            l.out = true;
            l.handler = handler;
            l.siteNo = siteNo;
            return l;
        }

        static PlanLine fail(TVehDueTask row, String stage, String reason) {
            PlanLine l = new PlanLine(row);
            l.out = false;
            l.stage = stage;
            l.reason = reason;
            l.siteNo = row.getSiteNo();
            return l;
        }
    }

    /** 派人时花名册上一位的当季负载快照 */
    private static class HandlerLoad {
        final TVehDueHandler h;
        int load;

        HandlerLoad(TVehDueHandler h) {
            this.h = h;
            this.load = h.getActiveCount() == null ? 0 : h.getActiveCount();
        }
    }

    /** 一次派人的结果：handler 为空即没派着，reason 写明卡在哪 */
    private static class AssignPick {
        final TVehDueHandler handler;
        final String reason;

        AssignPick(TVehDueHandler handler, String reason) {
            this.handler = handler;
            this.reason = reason;
        }
    }
}
