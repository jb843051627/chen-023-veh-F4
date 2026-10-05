package com.fc.v2.service;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 办事日历：把"不办事的日子"（国家放假、省里下文停办）逐日剔除后的日历算法。
 *
 * 数日子只按办事的日子数：一日一日往回点，点到不办事的整日就跳过不计数；
 * 夜里跨过去的那几个钟头不凑整日——止点带几点几分，开口点就还是几点几分，只挪日期。
 *
 * @author fuce
 * @date 2026-10-05
 */
public class VehDueWorkdayCalendar implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 不办事那一日的日子集合（键为墙钟日子 yyyy-MM-dd） */
    private final Set<String> nonworkDays;

    public VehDueWorkdayCalendar() {
        this(new HashSet<String>());
    }

    public VehDueWorkdayCalendar(Set<String> nonworkDays) {
        this.nonworkDays = nonworkDays == null ? new HashSet<String>() : new HashSet<String>(nonworkDays);
    }

    /** 这一日办不办事（只看日子，不看几点几分） */
    public boolean isWorkday(Date day) {
        return day != null && !nonworkDays.contains(dayKey(day));
    }

    /**
     * 从止点往回点 workdays 个办事日，落到的那一日同一时刻即为开口点。
     * workdays=0 时开口点就是止点本身（含那一刻）。
     */
    public Date openAt(Date dueAt, int workdays) {
        if (dueAt == null || workdays <= 0) {
            return dueAt;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTime(dueAt);
        // 一日一日往回走：办事日才计一个，不办事的整日跳过（会使开口日再往前挪一日）
        int counted = 0;
        while (counted < workdays) {
            cal.add(Calendar.DAY_OF_MONTH, -1);
            if (!nonworkDays.contains(dayKey(cal.getTime()))) {
                counted++;
            }
        }
        return cal.getTime();
    }

    /** 墙钟日子键：与挑桩、回写同一套读法，只认年月日，时辰不进键 */
    public static String dayKey(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd").format(d);
    }
}
