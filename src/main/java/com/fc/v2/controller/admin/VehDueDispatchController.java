package com.fc.v2.controller.admin;

import java.text.ParseException;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 证照到期批派一趟 Controller（新名目，只此一道门）。
 *
 * 挑中哪几桩、窗口两头静默段动不动手、递到谁名下、递不动怎么标——四问四答只认
 * 服务层 {@link ITVehDueDispatchService} 一处；到没到期那把尺也只在 VehDueRuler 一处定长短。
 * 本控制器只递话，不另摆第二把尺、不现轧任何一格。
 * 旧催办驱动 runOnce 照旧只出一个数，不回头改它的模样。
 *
 * @author fuce
 * @date 2026-10-05
 */
@Api(value = "证照到期批派")
@Controller
@RequestMapping("/vehDueDispatch")
public class VehDueDispatchController extends BaseController {

    /** 触发时刻的墙钟写法（与条目落库同一套说法，避开 JDBC 时区不对称） */
    private static final String FMT_TS = "yyyy-MM-dd HH:mm:ss";
    private static final String FMT_DAY = "yyyy-MM-dd";

    @Autowired
    private ITVehDueDispatchService vehDueDispatchService;

    @Log(title = "证照到期批派一趟", action = "dispatch")
    @ApiOperation(value = "走一趟批派",
            notes = "一天只开一段口（凌晨过两点起、五点前止）；静默段按下只应声、不动库。"
                    + "窗口内按办事日尺子挑桩，最多办五百条，三数回库逐条点过才出口")
    @PostMapping("/dispatch")
    @RequiresPermissions("veh:vehDueDispatch:dispatch")
    @ResponseBody
    public AjaxResult dispatch(String at) {
        Date moment = parseWallClock(at);
        if (at != null && !at.trim().isEmpty() && moment == null) {
            return error("触发那一刻写法不对，认 " + FMT_TS + " 或 " + FMT_DAY);
        }
        VehDueDispatchReceipt rc = vehDueDispatchService.dispatchBatch(moment);
        // 窗口静默、一条没挑着都是寻常收口，不作事故上报
        return AjaxResult.success(200, rc.getMark() == null ? "这一趟收口" : rc.getMark(), rc);
    }

    /**
     * 墙钟字符串解析：只按条目落库同一套墙钟说法解，不交给 JDBC 绑参。
     */
    private Date parseWallClock(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String t = text.trim();
        String pattern = t.length() <= FMT_DAY.length() ? FMT_DAY : FMT_TS;
        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        sdf.setLenient(false);
        try {
            return sdf.parse(t);
        } catch (ParseException e) {
            return null;
        }
    }
}
