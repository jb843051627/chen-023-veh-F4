package com.fc.v2.common.quartz.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fc.v2.model.custom.VehDueDispatchReceipt;
import com.fc.v2.service.ITVehDueDispatchService;

/**
 * 催办派批次调度名目（一批一办）。
 *
 * 这是**另起的一个名目**，专担"一趟只办一批"的活；原先驱动单轮催办的 {@link V2Task}
 * 照旧不添新东西、照旧只出一个数，不回头改它的模样。Quartz 里配
 * {@code vehDueDispatchTask.runBatch()} 即可（窗口判定在服务里，窗口外触发只应声不动库）。
 *
 * @author fuce
 * @date 2026-10-05
 */
@Component("vehDueDispatchTask")
public class VehDueDispatchTask {

    @Autowired
    private ITVehDueDispatchService vehDueDispatchService;

    /**
     * 走一趟：触发时刻取当下。凌晨两点起、五点前止才真动库，两头静默只应声；
     * 收口三数只报这一趟这一回，不并历史。
     */
    public void runBatch() {
        VehDueDispatchReceipt rc = vehDueDispatchService.runBatch(new java.util.Date());
        System.out.println("[催办派批次] " + rc.getMoment() + " " + rc.getMessage());
    }
}
