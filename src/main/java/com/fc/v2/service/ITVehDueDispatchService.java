package com.fc.v2.service;

import java.util.Date;

import com.fc.v2.model.custom.VehDueDispatchReceipt;

/**
 * 证照到期批派一趟 Service（新名目，与旧催办驱动 runOnce 分开）。
 *
 * 旧驱动照旧只出一个数；日后要"一趟只办一批"的活由这一处担，不回头改旧驱动的模样。
 * 两处口径一致：先挑哪几桩、到没到期，同由 {@link VehDueRuler} 一处定长短。
 *
 * @author fuce
 * @date 2026-10-05
 */
public interface ITVehDueDispatchService {

    /**
     * 走一趟批派。
     *
     * 一天只开一段口（凌晨过两点起、五点前止）；窗口两头静默段按下触发只应声、
     * 不动库、行上一格也不改。窗口内才挑桩、分派、回写、回库逐条点三数收口。
     */
    VehDueDispatchReceipt dispatchBatch(Date at);
}
