package com.fc.v2.controller.admin;

import java.text.SimpleDateFormat;
import java.util.Date;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.custom.VehDueDispatchReceipt;
import com.fc.v2.service.ITVehDueDispatchService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 证照到期催办——派批次 Controller（一批一办名目）。
 *
 * 控制器只递话：窗口在不在、挑中哪几桩、递到谁名下、分不出怎么标、三数怎么收口，
 * 全由 ITVehDueDispatchService 一处作答，这里不许另摆一把尺。
 * 旧的单轮催办（TVehDueTaskService.runOnce）不在这里，也不改它的模样。
 *
 * @author fuce
 * @date 2026-10-05
 */
@Api(value = "证照到期催办派批次")
@Controller
@RequestMapping("/vehDueDispatch")
public class VehDueDispatchController extends BaseController {

    @Autowired
    private ITVehDueDispatchService vehDueDispatchService;

    /** 问话时刻统一按墙钟字符串念（与库里挑桩同一把读法）；不传就取当下 */
    private Date parseAt(String at) {
        if (at == null || at.trim().isEmpty()) {
            return new Date();
        }
        try {
            String p = at.trim();
            return new SimpleDateFormat(p.length() <= 10 ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm:ss").parse(p);
        } catch (Exception e) {
            throw new IllegalArgumentException("问话时刻念不成，按 yyyy-MM-dd HH:mm:ss 递", e);
        }
    }

    @Log(title = "催办派批次屏上问讯", action = "plan")
    @ApiOperation(value = "屏上问一声：窗口、挑中哪几桩、递到谁名下、分不出怎么标（只看不动库）",
            notes = "屏上问讯与真走一趟同源，回的就是真走那一趟会算出来的")
    @GetMapping("/plan")
    @RequiresPermissions("vehDueDispatch:view")
    @ResponseBody
    public AjaxResult plan(String at) {
        VehDueDispatchReceipt rc = vehDueDispatchService.preview(parseAt(at));
        return AjaxResult.successData(200, rc);
    }

    @Log(title = "催办派批次走一趟", action = "run")
    @ApiOperation(value = "真走一趟：窗口外只应声不动库，窗口内挑桩派人，收口三数回库点过才出口",
            notes = "三数只报这一趟这一回，不并历史")
    @PostMapping("/run")
    @RequiresPermissions("vehDueDispatch:run")
    @ResponseBody
    public AjaxResult run(String at) {
        VehDueDispatchReceipt rc = vehDueDispatchService.runBatch(parseAt(at));
        return AjaxResult.successData(200, rc);
    }
}
