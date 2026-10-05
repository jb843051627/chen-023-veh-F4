package com.fc.v2.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
import com.fc.v2.service.impl.TVehDueDispatchServiceImpl;

/**
 * 证照到期批派一趟测试（不启 Spring，桩掉数据层；selectList 始终回同一份在册行，
 * 服务落成后在对象上的翻转即代表库里的状态，收口回点读的就是这份）。
 *
 * 钉住的坑：静默段不动库、压线开口、上趟派出不捞回、办事日剔假、废格逐条标不掀翻、
 * 销户/空联络人天生递不动、同户不并一人、休办/类别/定额顺延、双痕迹、回库现点三数、
 * 五百截断与先来先办排序、单条写砸不牵动别桩、空名单寻常收口。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TVehDueDispatchServiceImplTest {

    @Mock
    private TVehDueTaskMapper vehDueTaskMapper;
    @Mock
    private TVehNonworkDayMapper vehNonworkDayMapper;
    @Mock
    private TVehEntMapper vehEntMapper;
    @Mock
    private TVehDueHandlerMapper vehDueHandlerMapper;
    @Mock
    private TVehDueHandlerKindMapper vehDueHandlerKindMapper;

    @InjectMocks
    private TVehDueDispatchServiceImpl service;

    private static final String TYPE = "道路旅客运输业户";

    private List<TVehDueTask> tasks = new ArrayList<TVehDueTask>();
    private List<TVehEnt> ents = new ArrayList<TVehEnt>();
    private List<TVehDueHandler> handlers = new ArrayList<TVehDueHandler>();
    private List<TVehDueHandlerKind> kinds = new ArrayList<TVehDueHandlerKind>();
    private List<TVehNonworkDay> nonwork = new ArrayList<TVehNonworkDay>();

    private static Date ts(String s) {
        try {
            return new SimpleDateFormat(s.length() <= 10 ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long seq = 1;

    private TVehDueTask task(String itemNo, String dueAt, String amount, Integer status) {
        TVehDueTask t = new TVehDueTask();
        t.setId(seq++);
        t.setItemNo(itemNo);
        t.setDueAt(dueAt == null ? null : ts(dueAt));
        t.setAmount(amount == null ? null : new BigDecimal(amount));
        t.setStatus(status);
        t.setDelFlag(0);
        if (itemNo != null) {
            // 默认挂在册业户 YH00、联络人齐；个别用例再改
            t.setSiteNo("YH00");
            t.setContact("张三");
        }
        tasks.add(t);
        return t;
    }

    private TVehEnt ent(String siteNo, int status, String type) {
        TVehEnt e = new TVehEnt();
        e.setId((long) (siteNo.hashCode() & 0x7fffffff));
        e.setSiteNo(siteNo);
        e.setSiteName(siteNo + "业户");
        e.setSiteType(type);
        e.setStatus(status);
        e.setDelFlag(0);
        ents.add(e);
        return e;
    }

    private TVehDueHandler handler(String no, int status, Integer quota) {
        TVehDueHandler h = new TVehDueHandler();
        h.setId((long) (no.hashCode() & 0x7fffffff));
        h.setHandlerNo(no);
        h.setHandlerName(no);
        h.setStatus(status);
        h.setQuota(quota);
        h.setDelFlag(0);
        handlers.add(h);
        return h;
    }

    private TVehDueHandlerKind kind(String no, String type) {
        TVehDueHandlerKind k = new TVehDueHandlerKind();
        k.setId((long) ((no + type).hashCode() & 0x7fffffff));
        k.setHandlerNo(no);
        k.setSiteType(type);
        k.setDelFlag(0);
        kinds.add(k);
        return k;
    }

    private TVehNonworkDay nonwork(String day, int kind, String name) {
        TVehNonworkDay d = new TVehNonworkDay();
        d.setId((long) (day.hashCode() & 0x7fffffff));
        d.setDay(ts(day));
        d.setDayKind(kind);
        d.setDayName(name);
        d.setDelFlag(0);
        nonwork.add(d);
        return d;
    }

    private void stubCommon() {
        // 始终回同一份在册对象：服务落成翻转的就是"库里"的状态，收口回点读同一份
        when(vehDueTaskMapper.selectList(any())).thenReturn(tasks);
        when(vehEntMapper.selectList(any())).thenReturn(ents);
        when(vehDueHandlerMapper.selectList(any())).thenReturn(handlers);
        when(vehDueHandlerKindMapper.selectList(any())).thenReturn(kinds);
        when(vehNonworkDayMapper.selectList(any())).thenReturn(nonwork);
        when(vehDueTaskMapper.update(any(), any())).thenReturn(1);
    }

    private TVehDueTask find(String itemNo) {
        for (TVehDueTask t : tasks) {
            if (itemNo.equals(t.getItemNo())) {
                return t;
            }
        }
        return null;
    }

    @BeforeEach
    public void baseBook() {
        ent("YH00", 0, TYPE);
        handler("H1", 0, null);
        handler("H2", 0, null);
        kind("H1", TYPE);
        kind("H2", TYPE);
    }

    // ---------- 窗口两头的静默段 ----------

    @Test
    public void beforeWindowTouchesNothing() {
        stubCommon();
        TVehDueTask t = task("T1", "2026-10-01 00:00:00", "0", 0);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 01:59:59"));
        assertFalse(rc.getWindowOpen());
        assertNull(rc.getDispatchedCount());
        assertNull(rc.getFailedCount());
        assertNull(rc.getPendingCount());
        // 静默段：册子不读、行上一格不改
        verify(vehDueTaskMapper, never()).selectList(any());
        verify(vehDueTaskMapper, never()).update(any(), any());
        assertEquals(Integer.valueOf(0), t.getStatus());
    }

    @Test
    public void afterWindowTouchesNothing() {
        stubCommon();
        TVehDueTask t = task("T1", "2026-10-01 00:00:00", "0", 0);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 05:00:00"));
        assertFalse(rc.getWindowOpen());
        verify(vehDueTaskMapper, never()).update(any(), any());
        assertNull(t.getAssigneeNo());
    }

    @Test
    public void nullMomentTouchesNothing() {
        stubCommon();
        VehDueDispatchReceipt rc = service.dispatchBatch(null);
        assertFalse(rc.getWindowOpen());
        verify(vehDueTaskMapper, never()).selectList(any());
    }

    // ---------- 挑桩与压线 ----------

    @Test
    public void emptyDueIsOrdinaryClose() {
        stubCommon();
        task("FUTURE", "2026-12-01 03:30:00", "0", 0);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertTrue(rc.getWindowOpen());
        assertEquals("这一趟没有到期的", rc.getMark());
        assertEquals(0, rc.getDispatchedCount());
        assertEquals(0, rc.getFailedCount());
        assertEquals(1, rc.getPendingCount());
    }

    @Test
    public void dueExactlyAtTriggerIsPicked() {
        stubCommon();
        TVehDueTask t = task("T1", "2026-10-05 03:30:00", "0", 0);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(1, rc.getDispatchedCount());
        assertEquals(VehDueRuler.STATUS_DONE, t.getStatus());
        assertEquals("H1", t.getAssigneeNo());
        assertEquals("2026-10-05 03:30:00", VehDueRuler.ts(t.getDispatchedAt()));
    }

    @Test
    public void oneSecondBeforeDueStaysWaiting() {
        stubCommon();
        TVehDueTask t = task("T1", "2026-10-05 03:30:00", "0", 0);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:29:59"));
        assertEquals(0, rc.getDispatchedCount());
        assertEquals(Integer.valueOf(0), t.getStatus());
        assertEquals(1, rc.getPendingCount());
    }

    @Test
    public void alreadyDispatchedIsNotFishedBack() {
        stubCommon();
        TVehDueTask done = task("DONE", "2026-10-01 00:00:00", "0", 1);
        done.setAssigneeNo("H1");
        done.setDispatchedAt(ts("2026-10-03 03:00:00"));
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(0, rc.getDispatchedCount());
        assertEquals(0, rc.getFailedCount());
        // 历史已派出不计本趟任何一个数（也不是还欠开口）
        assertEquals(0, rc.getPendingCount());
    }

    @Test
    public void workdaysSkipNonworkDays() {
        // 止点 10-10（周日）03:30，可提前 2 个办事日；10-09 省停办、10-08 国家放假
        stubCommon();
        nonwork("2026-10-08", 0, "国庆");
        nonwork("2026-10-09", 1, "省停办");
        TVehDueTask t = task("T1", "2026-10-10 03:30:00", "2", 0);
        // 开口点 = 10-06 03:30（回点两日全是不办事日，再往前挪两个办事日）
        assertEquals(0, service.dispatchBatch(ts("2026-10-05 03:30:00")).getDispatchedCount());
        assertEquals(Integer.valueOf(0), t.getStatus());
        assertEquals(1, service.dispatchBatch(ts("2026-10-06 03:30:00")).getDispatchedCount());
        assertEquals(VehDueRuler.STATUS_DONE, t.getStatus());
    }

    // ---------- 行格撑不住：逐条标递不动，不掀翻这一趟 ----------

    @Test
    public void invalidRowsMarkedFailIndividuallyAndGoodRowStillGoes() {
        stubCommon();
        TVehDueTask noDue = task("NODUE", null, "0", 0);
        TVehDueTask negAmount = task("NEG", "2026-10-01 00:00:00", "-1", 0);
        TVehDueTask hugeAmount = task("HUGE", "2026-10-01 00:00:00", "99999", 0);
        TVehDueTask nullAmount = task("NULLAMT", "2026-10-01 00:00:00", null, 0);
        TVehDueTask noCode = task(null, "2026-10-01 00:00:00", "0", 0);
        noCode.setSiteNo("YH00");
        noCode.setContact("张三");
        TVehDueTask good = task("GOOD", "2026-10-01 00:00:00", "0", 0);

        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(1, rc.getDispatchedCount());
        assertEquals(5, rc.getFailedCount());
        assertEquals(VehDueRuler.STATUS_FAIL, noDue.getStatus());
        assertEquals(VehDueRuler.STAGE_DUE, noDue.getBlockStage());
        assertEquals(VehDueRuler.STAGE_AMOUNT, negAmount.getBlockStage());
        assertEquals(VehDueRuler.STAGE_AMOUNT, hugeAmount.getBlockStage());
        assertEquals(VehDueRuler.STAGE_AMOUNT, nullAmount.getBlockStage());
        assertEquals(VehDueRuler.STAGE_ITEM_NO, noCode.getBlockStage());
        assertTrue(noCode.getBlockReason() != null && !noCode.getBlockReason().isEmpty());
        assertEquals(VehDueRuler.STATUS_DONE, good.getStatus());
    }

    @Test
    public void deregisteredSiteAndMissingSiteAndEmptyContactAreBornFailures() {
        stubCommon();
        ent("YH01", 1, TYPE);
        TVehDueTask closed = task("CLOSED", "2026-10-01 00:00:00", "0", 0);
        closed.setSiteNo("YH01");
        TVehDueTask ghost = task("GHOST", "2026-10-01 00:00:00", "0", 0);
        ghost.setSiteNo("NO-SUCH");
        TVehDueTask noContact = task("NOCONTACT", "2026-10-01 00:00:00", "0", 0);
        noContact.setContact("  ");

        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(0, rc.getDispatchedCount());
        assertEquals(3, rc.getFailedCount());
        assertEquals(VehDueRuler.STAGE_SITE, closed.getBlockStage());
        assertEquals(VehDueRuler.STAGE_SITE, ghost.getBlockStage());
        assertEquals(VehDueRuler.STAGE_CONTACT, noContact.getBlockStage());
        // 天生递不动不必等到期：止点在未来也直接挂
        TVehDueTask futureClosed = task("FCLOSED", "2026-12-01 00:00:00", "0", 0);
        futureClosed.setSiteNo("YH01");
        // （上一趟已把前三条标完；这一条单独再走一趟验）
        tasks.removeAll(Arrays.asList(closed, ghost, noContact));
        VehDueDispatchReceipt rc2 = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(1, rc2.getFailedCount());
        assertEquals(VehDueRuler.STATUS_FAIL, futureClosed.getStatus());
    }

    // ---------- 递到谁名下 ----------

    @Test
    public void sameSiteGoesToDifferentHandlersByLeastLoad() {
        stubCommon();
        TVehDueTask a = task("A", "2026-10-01 03:30:00", "0", 0);
        TVehDueTask b = task("B", "2026-10-01 03:30:00", "0", 0);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(2, rc.getDispatchedCount());
        assertEquals("H1", a.getAssigneeNo());
        // 同一业户名下的几桩不许并到同一人：第二桩顺延给 H2
        assertEquals("H2", b.getAssigneeNo());
    }

    @Test
    public void offAndIncapableAndFullHandlersAreSkipped() {
        // 只剩 H2 在位且经得来；H1 休办，H3 经不来这一类，H4 定额已满
        handlers.clear();
        handler("H1", 1, null);
        handler("H2", 0, null);
        handler("H3", 0, null);
        handler("H4", 0, 0);
        kinds.clear();
        kind("H1", TYPE);
        kind("H2", TYPE);
        stubCommon();
        TVehDueTask a = task("A", "2026-10-01 03:30:00", "0", 0);
        TVehDueTask b = task("B", "2026-10-01 03:30:00", "0", 0);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(1, rc.getDispatchedCount());
        assertEquals("H2", a.getAssigneeNo());
        // H2 名下已压着同户，余者都不可接，当场标递不动并写明卡在派单
        assertEquals(VehDueRuler.STATUS_FAIL, b.getStatus());
        assertEquals(VehDueRuler.STAGE_ASSIGN, b.getBlockStage());
        assertTrue(b.getBlockReason().contains("定额") || b.getBlockReason().contains("同户"));
        assertEquals(1, rc.getFailedCount());
    }

    @Test
    public void noCapableHandlerFailsAtAssignStage() {
        handlers.clear();
        handler("H1", 0, null);
        kinds.clear();
        // H1 册里没挂这一类 → 经不来
        stubCommon();
        TVehDueTask t = task("T1", "2026-10-01 03:30:00", "0", 0);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(0, rc.getDispatchedCount());
        assertEquals(1, rc.getFailedCount());
        assertEquals(VehDueRuler.STAGE_ASSIGN, t.getBlockStage());
    }

    @Test
    public void handlerAlreadyHoldingSiteThisQuarterIsSkipped() {
        stubCommon();
        // 季内历史：H1 名下已压着 YH00 的一桩已派出
        TVehDueTask hist = task("HIST", "2026-10-02 00:00:00", "0", 1);
        hist.setSiteNo("YH00");
        hist.setAssigneeNo("H1");
        hist.setDispatchedAt(ts("2026-10-03 03:00:00"));
        TVehDueTask now = task("NOW", "2026-10-04 00:00:00", "0", 0);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(1, rc.getDispatchedCount());
        assertEquals("H2", now.getAssigneeNo());
    }

    // ---------- 五百截断 ----------

    @Test
    public void overFiveHundredOnlyFirstFiveHundredInDueThenCodeOrder() {
        stubCommon();
        // 501 桩同一止点、代号 T0001..T0501，各挂各的业户（避开同户相斥）
        for (int i = 1; i <= 501; i++) {
            String siteNo = String.format("S%04d", i);
            ent(siteNo, 0, TYPE);
            TVehDueTask t = task(String.format("T%04d", i), "2026-10-01 03:30:00", "0", 0);
            t.setSiteNo(siteNo);
        }
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        assertEquals(501, rc.getPickedCount());
        assertEquals(500, rc.getDispatchedCount());
        assertEquals(1, rc.getLeftToNext());
        assertEquals(VehDueRuler.STATUS_DONE, find("T0500").getStatus());
        // 末位留到下一趟：仍搁着；它已到期所以不计"还欠开口"
        assertEquals(Integer.valueOf(0), find("T0501").getStatus());
        assertEquals(0, rc.getPendingCount());
        assertTrue(rc.getMark().contains("500"));
    }

    // ---------- 单条写砸不牵动别桩；回库点不到不算办过 ----------

    @Test
    public void oneWriteThrowingDoesNotStopOthers() {
        stubCommon();
        TVehDueTask a = task("A", "2026-10-01 03:30:00", "0", 0);
        TVehDueTask b = task("B", "2026-10-01 03:30:00", "0", 0);
        when(vehDueTaskMapper.update(any(), any()))
                .thenThrow(new RuntimeException("库抖了一下"))
                .thenReturn(1);
        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        // A 写砸留在搁着（同户 YH00）；B 落成给 H1。三数回库点：递出去只点 B 一条
        assertEquals(1, rc.getDispatchedCount());
        assertEquals(0, rc.getFailedCount());
        assertEquals(Integer.valueOf(0), a.getStatus());
        assertEquals(VehDueRuler.STATUS_DONE, b.getStatus());
    }

    @Test
    public void dispatchedRowMissingTimestampIsNotCountedByRecount() {
        TVehDueTask t = task("T1", "2026-10-01 03:30:00", "0", 0);
        // 库里那一份：update 报落成，但回读时只翻词、没钉时刻（模拟两半痕迹缺一）
        TVehDueTask dbRow = task("__db__", "2026-10-01 03:30:00", "0", VehDueRuler.STATUS_DONE);
        dbRow.setId(t.getId());
        dbRow.setItemNo("T1");
        dbRow.setAssigneeNo("H1");
        dbRow.setDispatchedAt(null);
        when(vehEntMapper.selectList(any())).thenReturn(ents);
        when(vehDueHandlerMapper.selectList(any())).thenReturn(handlers);
        when(vehDueHandlerKindMapper.selectList(any())).thenReturn(kinds);
        when(vehNonworkDayMapper.selectList(any())).thenReturn(nonwork);
        when(vehDueTaskMapper.update(any(), any())).thenReturn(1);
        // 头一遍是挑桩读（搁着），收口回点读库里那一份（已派出但没时刻）
        when(vehDueTaskMapper.selectList(any())).thenReturn(tasks, java.util.Arrays.asList(dbRow));

        VehDueDispatchReceipt rc = service.dispatchBatch(ts("2026-10-05 03:30:00"));
        // 双痕迹缺一：回库逐条点时这一条不算递出去
        assertEquals(0, rc.getDispatchedCount());
    }
}
