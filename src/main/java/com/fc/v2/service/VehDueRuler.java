package com.fc.v2.service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import com.fc.v2.model.auto.TVehDueTask;

/**
 * 证照到期催办——"到没到期"唯一的一把尺。
 *
 * 新批派一趟与旧催办驱动都照这里的说法：
 * 1. 开口点 = 止点（due_at）往回点 amount 个办事日（不办事日历剔除国家放假、省停办日），
 *    夜里跨过去的几个钟头不凑整日，开口点保留止点的几点几分；
 * 2. 触发时刻已到开口点（含那一刻）即开口，逾期照催；
 * 3. 时刻读法两处一致：挑桩与回写同走墙钟字符串 yyyy-MM-dd HH:mm:ss，
 *    不把 java.util.Date 直接交 JDBC 绑参（serverTimezone 错位会使压线那刻错档）；
 * 4. 一趟只开一段口：凌晨过两点起、五点前止 [02:00, 05:00)。
 *
 * 行格撑不住的判法（新批派从严，旧驱动照旧兜底）也归这里一处出。
 *
 * @author fuce
 * @date 2026-10-05
 */
public final class VehDueRuler {

    /** 条目情形：搁着（存量空状态照搁着认） */
    public static final int STATUS_WAIT = 0;
    /** 条目情形：递出去了 */
    public static final int STATUS_DONE = 1;
    /** 条目情形：递不动 */
    public static final int STATUS_FAIL = 2;

    /** 提前日大得没边的顶：约十年还多的"提前几日"不当数（处里的证照最长一年一验） */
    public static final int MAX_AHEAD_DAYS = 3650;

    /** 一趟只办这一批的顶 */
    public static final int BATCH_CAP = 500;

    /** 卡环：止点时刻空着 */
    public static final String STAGE_DUE = "止点时刻";
    /** 卡环：提前日没数/带负号/大得没边 */
    public static final String STAGE_AMOUNT = "提前日";
    /** 卡环：条目代号没落 */
    public static final String STAGE_ITEM_NO = "条目代号";
    /** 卡环：业户销户/查无 */
    public static final String STAGE_SITE = "业户";
    /** 卡环：联络人空着 */
    public static final String STAGE_CONTACT = "联络人";
    /** 卡环：派单时分不到人 */
    public static final String STAGE_ASSIGN = "派单";

    private static final String FMT_TS = "yyyy-MM-dd HH:mm:ss";

    private VehDueRuler() {
    }

    /** 墙钟时刻字符串：全册唯一的时刻读法，挑桩、回写、压线比较同走这一套 */
    public static String ts(Date d) {
        return d == null ? null : new SimpleDateFormat(FMT_TS).format(d);
    }

    /** 这一桩是不是还搁着（存量空状态照搁着认） */
    public static boolean isWaiting(TVehDueTask r) {
        return r.getStatus() == null || r.getStatus() == STATUS_WAIT;
    }

    /**
     * 触发那一刻在不在开口窗口内：[02:00, 05:00)。
     * 两点以前、五点及以后都是静默段，按下触发只应声、不动库、不改一格。
     */
    public static boolean withinWindow(Date at) {
        if (at == null) {
            return false;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(at);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        return h >= 2 && h < 5;
    }

    /**
     * 提前日的从严读法（新批派一趟用）：没数、带负号、大得没边都不算数。
     * 念不成返回 null（行上记"提前日"卡环）；小数部分按整日口径截去（一天一天点满，没有半日）。
     */
    public static Integer parseAmountStrict(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal(MAX_AHEAD_DAYS)) > 0) {
            return null;
        }
        return amount.intValue();
    }

    /**
     * 提前日的兜底读法（旧驱动 runOnce 照旧不添新东西）：空着/负数都按 0（当日开口）。
     */
    public static int parseAmountLenient(BigDecimal amount) {
        Integer v = parseAmountStrict(amount);
        return v == null ? 0 : v;
    }

    /** 开口点：止点往回点 aheadDays 个办事日，同一时刻；aheadDays=0 即止点本身 */
    public static Date openAt(Date dueAt, int aheadDays, VehDueWorkdayCalendar calendar) {
        VehDueWorkdayCalendar cal = calendar == null ? new VehDueWorkdayCalendar() : calendar;
        return cal.openAt(dueAt, aheadDays);
    }

    /**
     * 这一桩在 at 那一刻开没开口：已到开口点（含那一刻）即开口，逾期照催。
     * 止点缺、开口点算不出的，这里不认它开口（从严那一趟先记废格，旧驱动照旧跳过）。
     */
    public static boolean isOpen(TVehDueTask r, Date at, Integer aheadDays, VehDueWorkdayCalendar calendar) {
        if (at == null || r.getDueAt() == null || aheadDays == null || aheadDays < 0) {
            return false;
        }
        Date open = openAt(r.getDueAt(), aheadDays, calendar);
        return ts(at).compareTo(ts(open)) >= 0;
    }

    /** 这一季的起头那一刻（一、四、七、十月一日零点），经办人手上活按季数 */
    public static Date quarterStart(Date at) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(at);
        int q = cal.get(Calendar.MONTH) / 3;
        cal.set(Calendar.MONTH, q * 3);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }
}
