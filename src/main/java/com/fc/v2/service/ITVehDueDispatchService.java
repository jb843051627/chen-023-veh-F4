package com.fc.v2.service;

import java.util.Date;

import com.fc.v2.model.custom.VehDueDispatchReceipt;

/**
 * 证照到期催办——派批次 Service（一批一办名目，与旧的单轮催办 ITVehDueTaskService 各走各的）。
 *
 * 旧的 {@code runOnce} 照旧只出一个数、不添新东西；一趟只办一批、要派人、要三数回点的活，
 * 都由这一个名目担着。窗口、到没到期那把尺、挑中哪几桩、递到谁名下、分不出怎么标，
 * 只在这一处的实现里定长短，控制器/调度只递话。
 *
 * @author fuce
 * @date 2026-10-05
 */
public interface ITVehDueDispatchService {

    /**
     * 屏上问一声：那一刻在不在窗口、会挑中哪几桩、各自递到谁名下/为何分不出、还欠开口几条。
     * 只看不动库，口径与 {@link #runBatch(Date)} 同源——回给屏上的就是真走那一趟会算出来的。
     */
    VehDueDispatchReceipt preview(Date at);

    /**
     * 真走一趟：窗口 [02:00,05:00) 外只应声不动库；窗口内挑桩、派人、两边落痕，
     * 收口三个数回库逐条点过才出口。
     */
    VehDueDispatchReceipt runBatch(Date at);
}
