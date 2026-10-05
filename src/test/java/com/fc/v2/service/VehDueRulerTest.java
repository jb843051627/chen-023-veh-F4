package com.fc.v2.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fc.v2.model.auto.TVehDueTask;

/**
 * 到期尺子与办事日历的单测：窗口压线、提前日从严/从宽、办事日回点、季头。
 */
public class VehDueRulerTest {

    private static Date ts(String s) {
        try {
            return new SimpleDateFormat(s.length() <= 10 ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static VehDueWorkdayCalendar cal(String... nonwork) {
        Set<String> d = new HashSet<String>(Arrays.asList(nonwork));
        return new VehDueWorkdayCalendar(d);
    }

    @Test
    public void windowIsTwoInclusiveFiveExclusive() {
        assertFalse(VehDueRuler.withinWindow(ts("2026-10-05 01:59:59")));
        assertTrue(VehDueRuler.withinWindow(ts("2026-10-05 02:00:00")));
        assertTrue(VehDueRuler.withinWindow(ts("2026-10-05 04:59:59")));
        // 五点整已在静默段
        assertFalse(VehDueRuler.withinWindow(ts("2026-10-05 05:00:00")));
        assertFalse(VehDueRuler.withinWindow(ts("2026-10-05 23:00:00")));
        assertFalse(VehDueRuler.withinWindow(null));
    }

    @Test
    public void strictAmountRejectsMissingNegativeAndBoundless() {
        assertNull(VehDueRuler.parseAmountStrict(null));
        assertNull(VehDueRuler.parseAmountStrict(new BigDecimal("-1")));
        assertNull(VehDueRuler.parseAmountStrict(new BigDecimal("3650.01")));
        assertEquals(Integer.valueOf(0), VehDueRuler.parseAmountStrict(new BigDecimal("0")));
        assertEquals(Integer.valueOf(2), VehDueRuler.parseAmountStrict(new BigDecimal("2")));
        // 没有半日：一天一天点满，小数截去
        assertEquals(Integer.valueOf(2), VehDueRuler.parseAmountStrict(new BigDecimal("2.9")));
        // 旧驱动从宽：空/负都兜底成 0
        assertEquals(0, VehDueRuler.parseAmountLenient(null));
        assertEquals(0, VehDueRuler.parseAmountLenient(new BigDecimal("-5")));
    }

    @Test
    public void openAtStepsBackWorkdaysKeepingClockTime() {
        VehDueWorkdayCalendar c = cal("2026-10-09");
        // 止点周一 10-12 03:30，提前 4 个办事日；回点时 10-11、10-10 各计一日，
        // 10-09 省停办跳过不计数，再点 10-08、10-07 凑满四个办事日：落点 10-07 03:30
        Date open = c.openAt(ts("2026-10-12 03:30:00"), 4);
        assertEquals("2026-10-07 03:30:00", VehDueRuler.ts(open));
        // 同样提前 2 日碰不上停办日：只回点两个自然日，仍是 03:30（夜里跨过的几个钟头不凑整日）
        Date openShort = c.openAt(ts("2026-10-12 03:30:00"), 2);
        assertEquals("2026-10-10 03:30:00", VehDueRuler.ts(openShort));
        // 没有停办日：回点两个自然日，仍是 03:30
        Date open2 = new VehDueWorkdayCalendar().openAt(ts("2026-10-12 03:30:00"), 2);
        assertEquals("2026-10-10 03:30:00", VehDueRuler.ts(open2));
    }

    @Test
    public void isOpenInclusiveAtOpenPoint() {
        TVehDueTask r = new TVehDueTask();
        r.setDueAt(ts("2026-10-10 03:30:00"));
        VehDueWorkdayCalendar c = new VehDueWorkdayCalendar();
        assertFalse(VehDueRuler.isOpen(r, ts("2026-10-10 03:29:59"), 0, c));
        // 止点正正等于触发那一刻，算这一趟该办
        assertTrue(VehDueRuler.isOpen(r, ts("2026-10-10 03:30:00"), 0, c));
        // 逾期照催
        assertTrue(VehDueRuler.isOpen(r, ts("2026-10-20 03:30:00"), 0, c));
    }

    @Test
    public void waitingTreatsNullStatusAsWaiting() {
        TVehDueTask r = new TVehDueTask();
        assertTrue(VehDueRuler.isWaiting(r));
        r.setStatus(VehDueRuler.STATUS_WAIT);
        assertTrue(VehDueRuler.isWaiting(r));
        r.setStatus(VehDueRuler.STATUS_DONE);
        assertFalse(VehDueRuler.isWaiting(r));
    }

    @Test
    public void quarterStartFallsOnFirstOfQuarterMonth() {
        assertEquals("2026-10-01 00:00:00", VehDueRuler.ts(VehDueRuler.quarterStart(ts("2026-10-05 03:30:00"))));
        assertEquals("2026-07-01 00:00:00", VehDueRuler.ts(VehDueRuler.quarterStart(ts("2026-07-01 00:00:00"))));
        assertEquals("2026-01-01 00:00:00", VehDueRuler.ts(VehDueRuler.quarterStart(ts("2026-03-31 23:59:59"))));
    }
}
