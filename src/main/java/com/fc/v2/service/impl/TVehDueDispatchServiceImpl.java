package com.fc.v2.service.impl;

import java.util.ArrayList;
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
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TVehDueHandlerKindMapper;
import com.fc.v2.mapper.auto.TVehDueHandlerMapper;
import com.fc.v2.mapper.auto.TVehDueTaskMapper;
import com.fc.v2.mapper.auto.TVehEntMapper;
import com.fc.v2.mapper.auto.TVehNonworkDayMapper;
import com.fc.v2.model.auto.TVehDueHandler;
import com.fc.v2.model.auto.TVehDueHandlerKind;
import com.fc.v2.model.auto.TVehDueTask;
import com.fc.v2.model.auto.TVehEnt;
import com.fc.v2.model.auto.TVehNonworkDay;
import com.fc.v2.model.custom.VehDueDispatchReceipt;
import com.fc.v2.model.custom.VehDueDispatchReceipt.ItemResult;
import com.fc.v2.service.ITVehDueDispatchService;
import com.fc.v2.service.VehDueRuler;
import com.fc.v2.service.VehDueWorkdayCalendar;

/**
 * 证照到期批派一趟（新名目）。
 *
 * 四问四答只在这一处给说法：挑中哪几桩（尺子 {@link VehDueRuler}）、窗口两头静默默不默
 * （[02:00,05:00)）、递到谁名下（季内活最少先接，休办/经不来/同户/塞满顺延）、
 * 递不动怎么标（卡环+缘由写回行上）。旧驱动 runOnce 不添新东西，两处共用同一把到期尺。
 *
 * @author fuce
 * @date 2026-10-05
 */
@Service
public class TVehDueDispatchServiceImpl implements ITVehDueDispatchService {

    @javax.annotation.Resource
    private TVehDueTaskMapper vehDueTaskMapper;
    @javax.annotation.Resource
    private TVehNonworkDayMapper vehNonworkDayMapper;
    @javax.annotation.Resource
    private TVehEntMapper vehEntMapper;
    @javax.annotation.Resource
    private TVehDueHandlerMapper vehDueHandlerMapper;
    @javax.annotation.Resource
    private TVehDueHandlerKindMapper vehDueHandlerKindMapper;

    @Override
    public VehDueDispatchReceipt dispatchBatch(Date at) {
        VehDueDispatchReceipt rc = new VehDueDispatchReceipt();
        rc.setBatchCap(VehDueRuler.BATCH_CAP);

        // 窗口两头的静默段（两点以前、五点及以后）按下触发：应一声、不动库、行上一格也不改
        if (at == null) {
            rc.setWindowOpen(false);
            rc.setMark("问话的那一刻缺省，这一趟不动库");
            return rc;
        }
        if (!VehDueRuler.withinWindow(at)) {
            rc.setWindowOpen(false);
            rc.setMark("此刻不在开口窗口（凌晨过两点起、五点前止），这一趟不动库");
            return rc;
        }
        rc.setWindowOpen(true);

        VehDueWorkdayCalendar calendar = loadCalendar();

        // 挑桩前先把册子整册读进来：搁着的才有资格，上趟递出去/递不动的不捞回来
        List<TVehDueTask> all = vehDueTaskMapper.selectList(
                new QueryWrapper<TVehDueTask>().eq("del_flag", 0));
        Map<String, TVehEnt> entBook = loadEntBook();

        List<TVehDueTask> picked = new ArrayList<TVehDueTask>();
        // 一桩一桩走完：废格与两类天生递不动当场标，撑不住不掀翻这一趟
        for (TVehDueTask r : all) {
            if (!VehDueRuler.isWaiting(r)) {
                continue;
            }
            String block = invalidStage(r, entBook);
            if (block != null) {
                markFail(r, block, invalidReason(block), rc);
                continue;
            }
            int aheadDays = VehDueRuler.parseAmountStrict(r.getAmount());
            // 行格都站得住，才轮到尺子开口：止点早于/正等于触发那一刻（含压线）就算到期
            if (VehDueRuler.isOpen(r, at, aheadDays, calendar)) {
                picked.add(r);
            }
            // 还差一口气的留在原地，等它自己到
        }

        rc.setPickedCount(picked.size());

        // 挑中的多过五百条：止点先来先办，同止点按代号从小到大，只办头五百，余下表里留着
        Collections.sort(picked, new Comparator<TVehDueTask>() {
            @Override
            public int compare(TVehDueTask a, TVehDueTask b) {
                int c = VehDueRuler.ts(a.getDueAt()).compareTo(VehDueRuler.ts(b.getDueAt()));
                if (c != 0) {
                    return c;
                }
                return a.getItemNo().compareTo(b.getItemNo());
            }
        });
        List<TVehDueTask> batch = picked;
        int left = 0;
        if (picked.size() > VehDueRuler.BATCH_CAP) {
            left = picked.size() - VehDueRuler.BATCH_CAP;
            batch = picked.subList(0, VehDueRuler.BATCH_CAP);
        }
        rc.setLeftToNext(left);

        if (picked.isEmpty() && rc.getItems().isEmpty()) {
            // 一条没挑着、也没桩被判废：寻常收口，不当事故上报
            rc.setMark("这一趟没有到期的");
        }

        // 递到谁名下：季内活最少先接，休办/经不来这类业户/名下已压着同一业户/塞满定额的顺延
        Assignment assignment = prepareAssignment(at, all);
        for (TVehDueTask r : batch) {
            TVehEnt ent = entBook.get(r.getSiteNo().trim());
            String handlerNo = assignment.pick(ent == null ? null : ent.getSiteType(),
                    r.getSiteNo().trim());
            if (handlerNo == null) {
                markFail(r, VehDueRuler.STAGE_ASSIGN, assignment.lastReason, rc);
                continue;
            }
            markDispatched(r, handlerNo, at, assignment, rc);
        }

        // 三个数回库逐条点过才出口：重新回册现点，不拿挑桩时屏上那一遍凑数
        recount(rc, at, calendar);

        if (left > 0) {
            String base = rc.getMark() == null ? "" : rc.getMark();
            rc.setMark((base.isEmpty() ? "" : base + "；")
                    + "挑中" + picked.size() + "条多过" + VehDueRuler.BATCH_CAP
                    + "条顶，只办头" + VehDueRuler.BATCH_CAP + "条，余下" + left + "条留到下一趟");
        }
        return rc;
    }

    /**
     * 行格撑不住/天生递不动的卡环；返回 null 表示站得住。
     * 次序：止点空 → 提前日没数/带负号/没边 → 代号没落 → 业户销户或查无 → 联络人空着几季。
     */
    private String invalidStage(TVehDueTask r, Map<String, TVehEnt> entBook) {
        if (r.getDueAt() == null) {
            return VehDueRuler.STAGE_DUE;
        }
        if (VehDueRuler.parseAmountStrict(r.getAmount()) == null) {
            return VehDueRuler.STAGE_AMOUNT;
        }
        if (r.getItemNo() == null || r.getItemNo().trim().isEmpty()) {
            return VehDueRuler.STAGE_ITEM_NO;
        }
        String siteNo = r.getSiteNo() == null ? null : r.getSiteNo().trim();
        if (siteNo == null || siteNo.isEmpty()) {
            return VehDueRuler.STAGE_SITE;
        }
        TVehEnt ent = entBook.get(siteNo);
        if (ent == null) {
            return VehDueRuler.STAGE_SITE;
        }
        if (ent.getStatus() != null && ent.getStatus() == 1) {
            // 所对业户已从总名录销户：天生递不动，不必空跑
            return VehDueRuler.STAGE_SITE;
        }
        if (r.getContact() == null || r.getContact().trim().isEmpty()) {
            // 联络人空着几季没人补：天生递不动，不必空跑
            return VehDueRuler.STAGE_CONTACT;
        }
        return null;
    }

    private String invalidReason(String stage) {
        if (VehDueRuler.STAGE_DUE.equals(stage)) {
            return "应开口的止点时刻空着，没法排开口点";
        }
        if (VehDueRuler.STAGE_AMOUNT.equals(stage)) {
            return "最可提前几日那一格没数、带负号或大得没边，数不出开口点";
        }
        if (VehDueRuler.STAGE_ITEM_NO.equals(stage)) {
            return "条目代号压根没落，没法指名";
        }
        if (VehDueRuler.STAGE_SITE.equals(stage)) {
            return "所对业户已从总名录销户，或名录上查无此代号";
        }
        if (VehDueRuler.STAGE_CONTACT.equals(stage)) {
            return "联络人那一栏空着几季没人补，派出去也接不上头";
        }
        return "行格撑不住";
    }

    /** 标递不动：状态翻成 2，卡环与缘由一并写回行上；单条写砸不牵动别条，写不进库不算本桩落成 */
    private void markFail(TVehDueTask r, String stage, String reason, VehDueDispatchReceipt rc) {
        boolean landed = false;
        try {
            int n = vehDueTaskMapper.update(null, new UpdateWrapper<TVehDueTask>()
                    .eq("id", r.getId())
                    .eq("del_flag", 0)
                    .and(w -> w.isNull("status").or().eq("status", VehDueRuler.STATUS_WAIT))
                    .set("status", VehDueRuler.STATUS_FAIL)
                    .set("block_stage", stage)
                    .set("block_reason", reason));
            if (n > 0) {
                r.setStatus(VehDueRuler.STATUS_FAIL);
                r.setBlockStage(stage);
                r.setBlockReason(reason);
                landed = true;
            }
        } catch (Exception e) {
            // 这一条写不回就留在搁着，别桩照旧一桩一桩走完
        }
        if (landed) {
            ItemResult ir = new ItemResult();
            ir.setId(r.getId());
            ir.setItemNo(r.getItemNo());
            ir.setResult(VehDueRuler.STATUS_FAIL);
            ir.setBlockStage(stage);
            ir.setBlockReason(reason);
            rc.getItems().add(ir);
        }
    }

    /** 标递出去了：状态翻成 1，派出那一刻与经办人同一回落上（只翻词不钉时刻不算办过） */
    private void markDispatched(TVehDueTask r, String handlerNo, Date at, Assignment assignment,
                                VehDueDispatchReceipt rc) {
        boolean landed = false;
        try {
            int n = vehDueTaskMapper.update(null, new UpdateWrapper<TVehDueTask>()
                    .eq("id", r.getId())
                    .eq("del_flag", 0)
                    .and(w -> w.isNull("status").or().eq("status", VehDueRuler.STATUS_WAIT))
                    .set("status", VehDueRuler.STATUS_DONE)
                    .set("assignee_no", handlerNo)
                    .set("dispatched_at", at));
            if (n > 0) {
                r.setStatus(VehDueRuler.STATUS_DONE);
                r.setAssigneeNo(handlerNo);
                r.setDispatchedAt(at);
                assignment.commit(handlerNo, r.getSiteNo().trim());
                landed = true;
            }
        } catch (Exception e) {
            // 这一条落不进库：名额不占（commit 在落成之后），行还搁着，下一趟再派
        }
        if (landed) {
            ItemResult ir = new ItemResult();
            ir.setId(r.getId());
            ir.setItemNo(r.getItemNo());
            ir.setResult(VehDueRuler.STATUS_DONE);
            ir.setAssigneeNo(handlerNo);
            ir.setDispatchedAt(VehDueRuler.ts(at));
            rc.getItems().add(ir);
        }
    }

    /**
     * 回库现点收口：重查整册逐条点，不拿挑桩时屏上那一遍凑数。
     * 递出去/递不动只点这一趟动过（按行 id 对）、库里确已落成的；
     * 还欠开口点此刻仍搁着、行格站得住、尺子说还没到开口点的（超顶留下的到期桩不在内）。
     */
    private void recount(VehDueDispatchReceipt rc, Date at, VehDueWorkdayCalendar calendar) {
        Set<Long> dispatchedIds = new HashSet<Long>();
        Set<Long> failedIds = new HashSet<Long>();
        for (ItemResult ir : rc.getItems()) {
            if (ir.getResult() == VehDueRuler.STATUS_DONE) {
                dispatchedIds.add(ir.getId());
            } else if (ir.getResult() == VehDueRuler.STATUS_FAIL) {
                failedIds.add(ir.getId());
            }
        }
        List<TVehDueTask> fresh = vehDueTaskMapper.selectList(
                new QueryWrapper<TVehDueTask>().eq("del_flag", 0));
        int dispatched = 0;
        int failed = 0;
        int pending = 0;
        for (TVehDueTask r : fresh) {
            Integer st = r.getStatus();
            // 库里须是"翻词 + 钉时刻 + 钉经办人"三样齐了才算递出去，且确属这一趟
            if (st != null && st == VehDueRuler.STATUS_DONE
                    && dispatchedIds.contains(r.getId())
                    && r.getDispatchedAt() != null
                    && r.getAssigneeNo() != null
                    && VehDueRuler.ts(r.getDispatchedAt()).equals(VehDueRuler.ts(at))) {
                dispatched++;
                continue;
            }
            // 递不动须是本趟 id 且卡环确实挂在行了
            if (st != null && st == VehDueRuler.STATUS_FAIL
                    && failedIds.contains(r.getId())
                    && r.getBlockStage() != null && !r.getBlockStage().isEmpty()) {
                failed++;
                continue;
            }
            // 还欠开口：仍搁着、行格站得住、尺子说此刻还没到开口点
            Integer ahead = VehDueRuler.parseAmountStrict(r.getAmount());
            if (VehDueRuler.isWaiting(r) && r.getDueAt() != null && ahead != null
                    && !VehDueRuler.isOpen(r, at, ahead, calendar)) {
                pending++;
            }
        }
        rc.setDispatchedCount(dispatched);
        rc.setFailedCount(failed);
        rc.setPendingCount(pending);
    }

    /** 读不办事日历：国家放假、省停办两类整日都剔出办事日 */
    private VehDueWorkdayCalendar loadCalendar() {
        Set<String> days = new HashSet<String>();
        try {
            List<TVehNonworkDay> rows = vehNonworkDayMapper.selectList(
                    new QueryWrapper<TVehNonworkDay>().eq("del_flag", 0));
            for (TVehNonworkDay d : rows) {
                if (d.getDay() != null) {
                    days.add(VehDueWorkdayCalendar.dayKey(d.getDay()));
                }
            }
        } catch (Exception e) {
            days.clear();
        }
        return new VehDueWorkdayCalendar(days);
    }

    /** 业户总名录（在册与已销户都要读：销户桩要照它判天生递不动），代号去重取最近落的一条 */
    private Map<String, TVehEnt> loadEntBook() {
        Map<String, TVehEnt> book = new HashMap<String, TVehEnt>();
        try {
            List<TVehEnt> rows = vehEntMapper.selectList(
                    new QueryWrapper<TVehEnt>().eq("del_flag", 0).orderByAsc("id"));
            for (TVehEnt e : rows) {
                if (e.getSiteNo() != null && !e.getSiteNo().trim().isEmpty()) {
                    book.put(e.getSiteNo().trim(), e);
                }
            }
        } catch (Exception e) {
            book.clear();
        }
        return book;
    }

    /**
     * 分派名册与这一季的手上活。
     * workload：本季起头（含）以来已递到名下且仍在递出去了状态的条数，活少的排前头；
     * 平手按代号排，保证两次给同一先后。
     */
    private Assignment prepareAssignment(Date at, List<TVehDueTask> all) {
        Assignment a = new Assignment();
        Date quarterStart = VehDueRuler.quarterStart(at);

        List<TVehDueHandler> handlers;
        try {
            handlers = vehDueHandlerMapper.selectList(
                    new QueryWrapper<TVehDueHandler>().eq("del_flag", 0));
        } catch (Exception e) {
            handlers = new ArrayList<TVehDueHandler>();
        }
        for (TVehDueHandler h : handlers) {
            if (h.getHandlerNo() == null || h.getHandlerNo().trim().isEmpty()) {
                continue;
            }
            Assignment.H hh = new Assignment.H();
            hh.no = h.getHandlerNo().trim();
            hh.off = h.getStatus() != null && h.getStatus() == 1;
            hh.quota = h.getQuota();
            a.handlers.put(hh.no, hh);
            a.order.add(hh.no);
        }

        // 各类业户谁经得来：册里没挂这一类，就当这类他经不来
        try {
            List<TVehDueHandlerKind> kinds = vehDueHandlerKindMapper.selectList(
                    new QueryWrapper<TVehDueHandlerKind>().eq("del_flag", 0));
            for (TVehDueHandlerKind k : kinds) {
                Assignment.H hh = a.handlers.get(k.getHandlerNo() == null ? null : k.getHandlerNo().trim());
                if (hh != null && k.getSiteType() != null && !k.getSiteType().trim().isEmpty()) {
                    hh.kinds.add(k.getSiteType().trim());
                }
            }
        } catch (Exception e) {
            // 类别册读不出来：没有一个人经得来任何类，下面逐桩会照派单卡环标递不动
        }

        // 季内已压在名下的活与已压着的业户（一家业户名下的几桩不并到同一人，跨本趟也认）
        for (TVehDueTask r : all) {
            if (r.getStatus() == null || r.getStatus() != VehDueRuler.STATUS_DONE
                    || r.getAssigneeNo() == null || r.getDispatchedAt() == null) {
                continue;
            }
            if (!r.getDispatchedAt().before(quarterStart)) {
                Assignment.H hh = a.handlers.get(r.getAssigneeNo().trim());
                if (hh != null) {
                    hh.load++;
                    if (r.getSiteNo() != null && !r.getSiteNo().trim().isEmpty()) {
                        hh.sites.add(r.getSiteNo().trim());
                    }
                }
            }
        }

        Collections.sort(a.order, new Comparator<String>() {
            @Override
            public int compare(String x, String y) {
                Assignment.H hx = a.handlers.get(x);
                Assignment.H hy = a.handlers.get(y);
                if (hx.load != hy.load) {
                    return hx.load - hy.load;
                }
                return x.compareTo(y);
            }
        });
        return a;
    }

    /** 分派名册的运行态 */
    private static class Assignment {
        static class H {
            String no;
            /** 这一季休办 */
            boolean off;
            /** 定额，null 为不限 */
            Integer quota;
            /** 季内已接活数（含本趟已 commit） */
            int load;
            /** 经得来的业户类别；空集表示一类都经不来 */
            Set<String> kinds = new HashSet<String>();
            /** 名下已压着的业户（季内历史 + 本趟） */
            Set<String> sites = new HashSet<String>();
        }

        final Map<String, H> handlers = new LinkedHashMap<String, H>();
        final List<String> order = new ArrayList<String>();
        /** 上一桩没分出去的缘由（写回行上用） */
        String lastReason;

        /**
         * 按先后挑一位：休办、经不来这一类、名下已压着同户、已塞满定额的逐位顺延。
         * 一位可用即定（调用方随后 commit 占位）。
         */
        synchronized String pick(String siteType, String siteNo) {
            lastReason = null;
            boolean sawCapable = false;
            for (String no : order) {
                H h = handlers.get(no);
                if (h.off) {
                    continue;
                }
                if (siteType == null || !h.kinds.contains(siteType)) {
                    continue;
                }
                if (h.sites.contains(siteNo)) {
                    // 这户的桩已有一桩在他名下，顺延给下一位
                    sawCapable = true;
                    continue;
                }
                if (h.quota != null && h.load >= h.quota) {
                    sawCapable = true;
                    continue;
                }
                return no;
            }
            lastReason = sawCapable
                    ? "在位且经得来这一类、名下没压着这户的经办人都已塞满定额或都已压着同户，分不出"
                    : "没有在位且经得来这类业户的经办人，分不出";
            return null;
        }

        synchronized void commit(String no, String siteNo) {
            H h = handlers.get(no);
            if (h != null) {
                h.load++;
                h.sites.add(siteNo);
                // 活数变了，先后次序下一桩重排：仍按季内活数（含本趟占位）最少先接
                Collections.sort(order, new Comparator<String>() {
                    @Override
                    public int compare(String x, String y) {
                        H hx = handlers.get(x);
                        H hy = handlers.get(y);
                        if (hx.load != hy.load) {
                            return hx.load - hy.load;
                        }
                        return x.compareTo(y);
                    }
                });
            }
        }
    }
}
