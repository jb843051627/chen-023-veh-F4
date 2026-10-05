package com.fc.v2.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TVehDueHandlerMapper;
import com.fc.v2.mapper.auto.TVehDueOffdayMapper;
import com.fc.v2.mapper.auto.TVehDueTaskMapper;
import com.fc.v2.mapper.auto.TVehEntMapper;
import com.fc.v2.model.auto.TVehDueHandler;
import com.fc.v2.model.auto.TVehDueOffday;
import com.fc.v2.model.auto.TVehDueTask;
import com.fc.v2.model.auto.TVehEnt;
import com.fc.v2.model.custom.VehDueDispatchReceipt;
import com.fc.v2.service.impl.TVehDueDispatchServiceImpl;

/**
 * 催办派批次测试（不启 Spring，桩掉四个数据层）。
 * 钉住：窗口两头静默不动库、办事日尺（跳假/不凑夜时/压线）、存量同尺、坏行各格、
 * 销户/无联络人天生分不出、派人最少负载与顺延、同户不并人、定额满当场分不出、
 * 五百截断排序、两痕齐留、单条回写砸了不掀趟、三数回库点、空名单寻常收口、preview 不动库。
 */
@ExtendWith(MockitoExtension.class)
public class TVehDueDispatchServiceImplTest {

    @Mock
    private TVehDueTaskMapper taskMapper;
    @Mock
    private TVehEntMapper entMapper;
    @Mock
    private TVehDueHandlerMapper handlerMapper;
    @Mock
    private TVehDueOffdayMapper offdayMapper;

    @InjectMocks
    private TVehDueDispatchServiceImpl service;

    private static final SimpleDateFormat DT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat DD = new SimpleDateFormat("yyyy-MM-dd");

    private static Date dt(String s) {
        try {
            return (s.length() <= 10 ? DD : DT).parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long seq = 1;

    private TVehDueTask task(String itemNo, String dueAt, String amount, Integer status) {
        TVehDueTask t = new TVehDueTask();
        t.setId(seq++);
        t.setItemNo(itemNo);
        t.setDueAt(dueAt == null ? null : dt(dueAt));
        t.setAmount(amount == null ? null : new BigDecimal(amount));
        t.setStatus(status);
        t.setDelFlag(0);
        t.setSiteNo("YH00");
        t.setContact("张三");
        return t;
    }

    private TVehEnt ent(String siteNo, int status, String siteType) {
        TVehEnt e = new TVehEnt();
        e.setId((long) (siteNo.hashCode() & 0x7fffffff) + 100);
        e.setSiteNo(siteNo);
        e.setSiteName(siteNo + "业户");
        e.setSiteType(siteType);
        e.setStatus(status);
        e.setDelFlag(0);
        return e;
    }

    private TVehDueHandler handler(String no, int leave, Integer quota, int active, String acceptTypes) {
        TVehDueHandler h = new TVehDueHandler();
        h.setId(Long.decode(no.replaceAll("\\D", "")) + 200);
        h.setHandlerNo(no);
        h.setHandlerName(no);
        h.setLeaveFlag(leave);
        h.setQuota(quota);
        h.setActiveCount(active);
        h.setAcceptTypes(acceptTypes);
        h.setStatus(0);
        h.setDelFlag(0);
        return h;
    }

    private TVehDueOffday offday(String day) {
        TVehDueOffday d = new TVehDueOffday();
        d.setId(300L + Integer.parseInt(day.substring(8)));
        d.setDay(dt(day));
        d.setDayKind(0);
        d.setDelFlag(0);
        return d;
    }

    /** 桩掉"回库"：selectList 始终返回这批活对象；updateById 把非空字段拷到原对象，模拟真落库。
     *  lenient：窗口静默/空名单那几趟一格不动，桩没人碰也不该算错。 */
    private List<TVehDueTask> stubDb(TVehDueTask... tasks) {
        final List<TVehDueTask> live = new ArrayList<TVehDueTask>(Arrays.asList(tasks));
        org.mockito.Mockito.lenient().when(taskMapper.selectList(any())).thenReturn(live);
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            TVehDueTask upd = inv.getArgument(0);
            for (TVehDueTask t : live) {
                if (t.getId().equals(upd.getId())) {
                    if (upd.getStatus() != null) {
                        t.setStatus(upd.getStatus());
                    }
                    if (upd.getHandlerNo() != null) {
                        t.setHandlerNo(upd.getHandlerNo());
                    }
                    if (upd.getDispatchedAt() != null) {
                        t.setDispatchedAt(upd.getDispatchedAt());
                    }
                    if (upd.getFailStage() != null) {
                        t.setFailStage(upd.getFailStage());
                    }
                    if (upd.getFailReason() != null) {
                        t.setFailReason(upd.getFailReason());
                    }
                    return 1;
                }
            }
            return 0;
        }).when(taskMapper).updateById(any(TVehDueTask.class));
        return live;
    }

    /**
     * 服务按 eq("site_no", x).eq("del_flag",0) 查业户；mybatis-plus 3.4.2 要先渲染 SQL 段
     * （getSqlSegment）才把参数刷进 paramNameValuePairs。site_no 落在参数里，照它过滤。
     */
    private void stubEnts(final TVehEnt... ents) {
        org.mockito.Mockito.lenient().when(entMapper.selectList(any())).thenAnswer(inv -> {
            @SuppressWarnings("rawtypes")
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper w = inv.getArgument(0);
            List<Object> params = new ArrayList<Object>();
            if (w != null) {
                w.getSqlSegment(); // 逼参数落进 paramNameValuePairs（重新桩时 Mockito 会以空参触发一次旧 answer）
                params = new ArrayList<Object>(w.getParamNameValuePairs().values());
            }
            List<TVehEnt> out = new ArrayList<TVehEnt>();
            for (TVehEnt e : ents) {
                if (params.contains(e.getSiteNo())) {
                    out.add(e);
                }
            }
            return out;
        });
    }

    private void stubHandlers(TVehDueHandler... hs) {
        org.mockito.Mockito.lenient().when(handlerMapper.selectList(any()))
                .thenReturn(new ArrayList<TVehDueHandler>(Arrays.asList(hs)));
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            TVehDueHandler upd = inv.getArgument(0);
            for (TVehDueHandler h : hs) {
                if (h.getId().equals(upd.getId()) && upd.getActiveCount() != null) {
                    h.setActiveCount(upd.getActiveCount());
                }
            }
            return 1;
        }).when(handlerMapper).updateById(any(TVehDueHandler.class));
    }

    private void stubOffdays(String... days) {
        List<TVehDueOffday> list = new ArrayList<TVehDueOffday>();
        for (String d : days) {
            list.add(offday(d));
        }
        when(offdayMapper.selectList(any())).thenReturn(list);
    }

    // ---------- 窗口 ----------

    @Test
    public void silentBeforeTwoAndAfterFiveTouchesNothing() {
        TVehDueTask t = task("T1", "2026-10-01 00:00:00", "0", 0);
        stubDb(t);
        // 01:59:59 静默
        VehDueDispatchReceipt r1 = service.runBatch(dt("2026-10-05 01:59:59"));
        assertFalse(r1.isActed());
        // 05:00:00 静默
        VehDueDispatchReceipt r2 = service.runBatch(dt("2026-10-05 05:00:00"));
        assertFalse(r2.isActed());
        verify(taskMapper, never()).updateById(any(TVehDueTask.class));
        assertEquals(Integer.valueOf(0), t.getStatus());
    }

    @Test
    public void windowEdgesTwoInclusiveFiveExclusive() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"), ent("YH01", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        TVehDueTask a = task("A", "2026-10-05 02:00:00", "0", 0);
        TVehDueTask b = task("B", "2026-10-05 02:00:00", "0", 0);
        b.setSiteNo("YH01");
        stubDb(a, b);
        // 恰好两点：开口
        VehDueDispatchReceipt r2 = service.runBatch(dt("2026-10-05 02:00:00"));
        assertEquals(2, r2.getDispatchedCount());
        // 换一批新活，04:59:59 仍开口
        TVehDueTask c = task("C", "2026-10-06 04:59:59", "0", 0);
        stubDb(c);
        VehDueDispatchReceipt r4 = service.runBatch(dt("2026-10-06 04:59:59"));
        assertEquals(1, r4.getDispatchedCount());
    }

    @Test
    public void nullMomentDoesNothing() {
        VehDueDispatchReceipt r = service.runBatch(null);
        assertFalse(r.isActed());
        verify(taskMapper, never()).updateById(any(TVehDueTask.class));
    }

    // ---------- 择期尺 ----------

    @Test
    public void businessDayRulerSkipsHolidays() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays("2026-10-08", "2026-10-09");
        // 止点 10-10，提前 2 个办事日：09、08 都在假册上不凑，开口点落在 10-06
        TVehDueTask t = task("T1", "2026-10-10 00:00:00", "2", 0);
        stubDb(t);
        assertEquals(0, service.runBatch(dt("2026-10-05 03:00:00")).getDispatchedCount());
        assertEquals(1, service.runBatch(dt("2026-10-06 03:00:00")).getDispatchedCount());
    }

    @Test
    public void overnightHoursDoNotMakeAWholeDayAndLineIsInclusive() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        // 止点 10-09 03:30，提前 1 个办事日 => 开口 10-08 03:30；夜里跨过去的钟点不凑整日
        TVehDueTask t = task("T1", "2026-10-09 03:30:00", "1", 0);
        stubDb(t);
        // 都在凌晨窗口内问：差一秒没到开口点
        assertEquals(0, service.runBatch(dt("2026-10-08 03:29:59")).getDispatchedCount());
        // 正正等于开口那一刻也算这一趟该办
        assertEquals(1, service.runBatch(dt("2026-10-08 03:30:00")).getDispatchedCount());
    }

    @Test
    public void legacyRowsMeasuredBySameRuler() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        // 存量行状态空着：按待派认，提前日照它自己那格，不因录得早就另给说法
        TVehDueTask legacy = task("LEG", "2026-10-10 00:00:00", "3", null);
        stubDb(legacy);
        assertEquals(0, service.runBatch(dt("2026-10-06 03:00:00")).getDispatchedCount());
        assertEquals(1, service.runBatch(dt("2026-10-07 03:00:00")).getDispatchedCount());
    }

    @Test
    public void alreadyDispatchedNotScoopedBack() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        TVehDueTask done = task("DONE", "2026-10-01 00:00:00", "0", 1);
        TVehDueTask waiting = task("WAIT", "2026-10-01 00:00:00", "0", 0);
        stubDb(done, waiting);
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(1, r.getDispatchedCount());
        assertEquals("WAIT", r.getItems().get(0).getItemNo());
    }

    // ---------- 坏行 ----------

    @Test
    public void brokenRowsMarkedFailAndDoNotStopOthers() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        TVehDueTask noItem = task(null, "2026-10-01 00:00:00", "0", 0);
        TVehDueTask noDue = task("NODUE", null, "0", 0);
        TVehDueTask noAmount = task("NOAMT", "2026-10-01 00:00:00", null, 0);
        TVehDueTask neg = task("NEG", "2026-10-01 00:00:00", "-1", 0);
        TVehDueTask huge = task("HUGE", "2026-10-01 00:00:00", "99999", 0);
        TVehDueTask good = task("GOOD", "2026-10-01 00:00:00", "0", 0);
        stubDb(noItem, noDue, noAmount, neg, huge, good);
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(1, r.getDispatchedCount());
        assertEquals(5, r.getFailCount());
        for (VehDueDispatchReceipt.Item it : r.getItems()) {
            if (VehDueDispatchReceipt.OUT_FAIL.equals(it.getOutcome())) {
                assertEquals("校格", it.getFailStage());
                assertNotNull(it.getFailReason());
            }
        }
        // 坏行真落了"分不出"
        assertEquals(Integer.valueOf(2), noDue.getStatus());
    }

    // ---------- 天生分不出 ----------

    @Test
    public void cancelledEnterpriseAndEmptyContactAreBornFail() {
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        TVehDueTask cancelled = task("CAN", "2026-10-01 00:00:00", "0", 0);
        cancelled.setSiteNo("YH01");
        TVehDueTask noContact2 = task("NOCON2", "2026-10-01 00:00:00", "0", 0);
        noContact2.setSiteNo("YH00");
        noContact2.setContact("  ");
        stubDb(cancelled, noContact2);
        stubEnts(ent("YH01", 1, "危险货物运输业户"), ent("YH00", 0, "道路旅客运输业户"));
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(0, r.getDispatchedCount());
        assertEquals(2, r.getFailCount());
        Map<String, String> stageByItem = new HashMap<String, String>();
        for (VehDueDispatchReceipt.Item it : r.getItems()) {
            stageByItem.put(it.getItemNo(), it.getFailStage());
        }
        assertEquals("销户", stageByItem.get("CAN"));
        assertEquals("无联络人", stageByItem.get("NOCON2"));
    }

    // ---------- 派人 ----------

    @Test
    public void leastLoadedFirstAndLeaveTypeSkips() {
        // H1 休办、H2 只接客运（本桩是危货）、H3 在岗负载 5、H4 在岗负载 2 -> 落到 H4
        stubEnts(ent("YH00", 0, "危险货物运输业户"));
        stubHandlers(
                handler("H1", 1, null, 0, null),
                handler("H2", 0, null, 0, "道路旅客运输业户"),
                handler("H3", 0, null, 5, null),
                handler("H4", 0, null, 2, null));
        stubOffdays();
        TVehDueTask t = task("T1", "2026-10-01 00:00:00", "0", 0);
        stubDb(t);
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(1, r.getDispatchedCount());
        assertEquals("H4", r.getItems().get(0).getHandlerNo());
    }

    @Test
    public void sameEnterpriseMustNotBundleToOneHandler() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(
                handler("H1", 0, null, 0, null),
                handler("H2", 0, null, 0, null));
        stubOffdays();
        TVehDueTask a = task("A1", "2026-10-01 00:00:00", "0", 0);
        TVehDueTask b = task("A2", "2026-10-01 00:00:00", "0", 0);
        stubDb(a, b);
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(2, r.getDispatchedCount());
        Map<String, String> who = new HashMap<String, String>();
        for (VehDueDispatchReceipt.Item it : r.getItems()) {
            who.put(it.getItemNo(), it.getHandlerNo());
        }
        assertEquals("H1", who.get("A1"));
        assertEquals("H2", who.get("A2"));
    }

    @Test
    public void quotaFullFailsImmediatelyWithoutSkippingToNext() {
        // 更空的 H1 这季休办先被跳过，轮到 H2 时其定额已塞满：当场分不出，
        // 不许顺延给排在后面、空闲的 H3
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(
                handler("H1", 1, null, 0, null),
                handler("H2", 0, 1, 1, null),
                handler("H3", 0, null, 5, null));
        stubOffdays();
        TVehDueTask t = task("T1", "2026-10-01 00:00:00", "0", 0);
        stubDb(t);
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(0, r.getDispatchedCount());
        assertEquals(1, r.getFailCount());
        assertEquals("派人", r.getItems().get(0).getFailStage());
        assertNull(t.getHandlerNo());
    }

    @Test
    public void noHandlerAvailableFails() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 1, null, 0, null)); // 花名册只剩休办的
        stubOffdays();
        TVehDueTask t = task("T1", "2026-10-01 00:00:00", "0", 0);
        stubDb(t);
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(1, r.getFailCount());
        assertEquals("派人", r.getItems().get(0).getFailStage());
    }

    // ---------- 留痕两痕 ----------

    @Test
    public void dispatchLeavesBothWordAndTimestamp() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        TVehDueTask t = task("T1", "2026-10-01 00:00:00", "0", 0);
        stubDb(t);
        Date at = dt("2026-10-05 03:30:00");
        service.runBatch(at);
        assertEquals(Integer.valueOf(1), t.getStatus());
        assertEquals("H1", t.getHandlerNo());
        assertEquals(DT.format(at), DT.format(t.getDispatchedAt()));
    }

    @Test
    public void incompleteTracesOnRecountCountAsNotDone() {
        // 回库那一趟与屏上那一趟对不上：库里只翻了词、没钉时刻——这桩算没走完，按分不出（环：回写）
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        TVehDueTask t = task("T1", "2026-10-01 00:00:00", "0", 0);
        final List<TVehDueTask> live = new ArrayList<TVehDueTask>(Arrays.asList(t));
        org.mockito.Mockito.lenient().when(taskMapper.selectList(any()))
                .thenReturn(live)
                .thenReturn(new ArrayList<TVehDueTask>(Arrays.asList(t)));
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            TVehDueTask upd = inv.getArgument(0);
            // 落库这一下只翻词，时刻/经办人都没落（模拟残缺回写）
            t.setStatus(upd.getStatus());
            return 1;
        }).when(taskMapper).updateById(any(TVehDueTask.class));
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:30:00"));
        assertEquals(0, r.getDispatchedCount());
        assertEquals(1, r.getFailCount());
        assertEquals("回写", r.getItems().get(0).getFailStage());
    }

    // ---------- 五百截断 ----------

    @Test
    public void capAtFiveHundredOrderedByDueThenItemNo() {
        // 一户一桩（避开"同户不并人"），五百条同止点、两条更早，验排序与截断
        TVehEnt[] ents = new TVehEnt[502];
        for (int i = 0; i < 502; i++) {
            ents[i] = ent(String.format("S%03d", i), 0, "道路旅客运输业户");
        }
        stubEnts(ents);
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        List<TVehDueTask> all = new ArrayList<TVehDueTask>();
        TVehDueTask early2 = task("EARLY2", "2026-09-30 00:00:00", "0", 0);
        early2.setSiteNo("S500");
        TVehDueTask early1 = task("EARLY1", "2026-09-30 00:00:00", "0", 0);
        early1.setSiteNo("S501");
        all.add(early2);
        all.add(early1);
        for (int i = 0; i < 500; i++) {
            TVehDueTask t = task(String.format("T%03d", i), "2026-10-01 00:00:00", "0", 0);
            t.setSiteNo(String.format("S%03d", i));
            all.add(t);
        }
        stubDb(all.toArray(new TVehDueTask[0]));
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(500, r.getDispatchedCount());
        assertEquals(2, r.getLeftoverCount());
        // 先办的两条是 09-30，按代号 EARLY1 在 EARLY2 前
        assertEquals("EARLY1", r.getItems().get(0).getItemNo());
        assertEquals("EARLY2", r.getItems().get(1).getItemNo());
        // 余下的是 10-01 里代号最大的两条 T498/T499
        assertTrue(r.getLeftovers().contains("T499"));
        assertTrue(r.getLeftovers().contains("T498"));
        // 留下的仍是待派，算"还欠开口"
        assertEquals(2, r.getWaitingCount());
    }

    // ---------- 回写抖动 ----------

    @Test
    public void oneWriteFailureMarkedFailDoesNotStopRun() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"), ent("YH01", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        TVehDueTask a = task("A", "2026-10-01 00:00:00", "0", 0);
        TVehDueTask b = task("B", "2026-10-01 00:00:00", "0", 0);
        b.setSiteNo("YH01");
        List<TVehDueTask> live = new ArrayList<TVehDueTask>(Arrays.asList(a, b));
        org.mockito.Mockito.lenient().when(taskMapper.selectList(any())).thenReturn(live);
        // 头一回 updateById（派 A）砸了，紧接着的补挂分不出成功；B 正常派成
        org.mockito.Mockito.doAnswer(inv -> {
            TVehDueTask upd = inv.getArgument(0);
            if (upd.getId().equals(a.getId()) && Integer.valueOf(1).equals(upd.getStatus())
                    && upd.getFailStage() == null) {
                throw new RuntimeException("库抖了一下");
            }
            for (TVehDueTask t : live) {
                if (t.getId().equals(upd.getId())) {
                    if (upd.getStatus() != null) {
                        t.setStatus(upd.getStatus());
                    }
                    if (upd.getHandlerNo() != null) {
                        t.setHandlerNo(upd.getHandlerNo());
                    }
                    if (upd.getDispatchedAt() != null) {
                        t.setDispatchedAt(upd.getDispatchedAt());
                    }
                    if (upd.getFailStage() != null) {
                        t.setFailStage(upd.getFailStage());
                    }
                    if (upd.getFailReason() != null) {
                        t.setFailReason(upd.getFailReason());
                    }
                    return 1;
                }
            }
            return 0;
        }).when(taskMapper).updateById(any(TVehDueTask.class));
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(1, r.getDispatchedCount());
        assertEquals(1, r.getFailCount());
        assertEquals(Integer.valueOf(2), a.getStatus());
        assertEquals("回写", a.getFailStage());
        assertEquals(Integer.valueOf(1), b.getStatus());
    }

    // ---------- 收口 ----------

    @Test
    public void emptySelectionIsNormalClose() {
        stubOffdays();
        stubDb(task("FUTURE", "2026-12-01 00:00:00", "0", 0));
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(0, r.getDispatchedCount());
        assertEquals(0, r.getFailCount());
        assertEquals(1, r.getWaitingCount());
        assertTrue(r.getMessage().contains("没有到期的"));
    }

    @Test
    public void countsReportedAreThisRunOnly() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        // 上趟的已派出/分不出留在库里，不并进这一趟的数
        TVehDueTask oldDone = task("OLDD", "2026-09-01 00:00:00", "0", 1);
        TVehDueTask oldFail = task("OLDF", "2026-09-01 00:00:00", "0", 2);
        TVehDueTask now = task("NOW", "2026-10-01 00:00:00", "0", 0);
        TVehDueTask later = task("LATER", "2026-12-01 00:00:00", "0", 0);
        stubDb(oldDone, oldFail, now, later);
        VehDueDispatchReceipt r = service.runBatch(dt("2026-10-05 03:00:00"));
        assertEquals(1, r.getDispatchedCount());
        assertEquals(0, r.getFailCount());
        assertEquals(1, r.getWaitingCount());
    }

    @Test
    public void previewDoesNotWriteButMatchesRun() {
        stubEnts(ent("YH00", 0, "道路旅客运输业户"));
        stubHandlers(handler("H1", 0, null, 0, null));
        stubOffdays();
        TVehDueTask t = task("T1", "2026-10-01 00:00:00", "0", 0);
        stubDb(t);
        Date at = dt("2026-10-05 03:00:00");
        VehDueDispatchReceipt p = service.preview(at);
        assertFalse(p.isActed());
        assertEquals(1, p.getDispatchedCount());
        assertEquals("H1", p.getItems().get(0).getHandlerNo());
        // 屏上问过一声，库里一格没动
        assertEquals(Integer.valueOf(0), t.getStatus());
        assertNull(t.getHandlerNo());
        verify(taskMapper, never()).updateById(any(TVehDueTask.class));
    }
}
