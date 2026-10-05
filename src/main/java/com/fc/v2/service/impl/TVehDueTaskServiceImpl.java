package com.fc.v2.service.impl;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TVehDueTaskMapper;
import com.fc.v2.mapper.auto.TVehNonworkDayMapper;
import com.fc.v2.model.auto.TVehDueTask;
import com.fc.v2.model.auto.TVehNonworkDay;
import com.fc.v2.service.ITVehDueTaskService;
import com.fc.v2.service.VehDueRuler;
import com.fc.v2.service.VehDueWorkdayCalendar;

/**
 * 证照到期催办条目 Service业务层处理（旧驱动：scheduling-job 形状，照旧只出一个数）
 *
 * 模样不添新东西：仍是 {@link #listDue} 出名单、{@link #runOnce} 照名单派、返回成功条数。
 * 但"到没到期"那把尺只在 {@link VehDueRuler} 一处：开口点按办事日往回点
 * （国家放假、省停办不凑数），新批派一趟与这里同用它，两处不许各摆一把。
 * 旧驱动的兜底口径照旧：amount 空着/带负号按 0（当日开口），存量空状态照待派认。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TVehDueTaskServiceImpl implements ITVehDueTaskService {

    @javax.annotation.Resource
    private TVehDueTaskMapper vehDueTaskMapper;

    /** 不办事日历：新批派与旧驱动同读这一册（无配置时每日都办事，不添新门槛） */
    @javax.annotation.Resource
    private TVehNonworkDayMapper vehNonworkDayMapper;

    @Override
    public TVehDueTask selectTVehDueTaskById(Long id) {
        return this.vehDueTaskMapper.selectById(id);
    }

    @Override
    public List<TVehDueTask> listDue(Date at) {
        List<TVehDueTask> due = new java.util.ArrayList<TVehDueTask>();
        if (at == null) {
            // 问话时刻都没给：还一份空名单作兜底，不许把异常甩给调用方
            return due;
        }
        VehDueWorkdayCalendar calendar = loadCalendar();
        List<TVehDueTask> all = this.vehDueTaskMapper.selectList(
                new QueryWrapper<TVehDueTask>().eq("del_flag", 0));
        for (TVehDueTask r : all) {
            if (isDispatchable(r, at, calendar)) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    public int runOnce(Date at) {
        // 直接照开口名单派，不另筛第二遍：名单与派出口径同源，结存才对得齐
        List<TVehDueTask> due = listDue(at);
        int ok = 0;
        for (TVehDueTask r : due) {
            try {
                TVehDueTask upd = new TVehDueTask();
                upd.setId(r.getId());
                upd.setStatus(VehDueRuler.STATUS_DONE);
                if (this.vehDueTaskMapper.updateById(upd) > 0) {
                    ok++;
                }
            } catch (Exception e) {
                // 单条派砸了不牵动别条：这一条留在待派，下一轮再派
            }
        }
        return ok;
    }

    /**
     * 一条这一轮派不派得动，只此一处判定，listDue 与 runOnce 都照尺子走：
     * 未派过、应开口止点不缺、问话时刻已到开口点（止点按办事日往前最多 amount 日，含开口那一刻）。
     * 旧驱动从宽：amount 空着/负数按 0 兜底；判据缺一格的跳过，不许抛异常出去。
     */
    private boolean isDispatchable(TVehDueTask r, Date at, VehDueWorkdayCalendar calendar) {
        if (!VehDueRuler.isWaiting(r)) {
            // 已派出、已递不动的不再派；只有搁着（含存量空着）才开口
            return false;
        }
        if (r.getDueAt() == null) {
            return false;
        }
        int aheadDays = VehDueRuler.parseAmountLenient(r.getAmount());
        return VehDueRuler.isOpen(r, at, aheadDays, calendar);
    }

    /** 读不办事日历：国家放假、省停办那两类整日都剔出办事日 */
    private VehDueWorkdayCalendar loadCalendar() {
        Set<String> days = new HashSet<String>();
        if (vehNonworkDayMapper != null) {
            try {
                List<TVehNonworkDay> rows = vehNonworkDayMapper.selectList(
                        new QueryWrapper<TVehNonworkDay>().eq("del_flag", 0));
                for (TVehNonworkDay d : rows) {
                    if (d.getDay() != null) {
                        days.add(VehDueWorkdayCalendar.dayKey(d.getDay()));
                    }
                }
            } catch (Exception e) {
                // 日历读不出来不掀翻旧驱动：按每日都办事兜底，与无配置时一致
                days.clear();
            }
        }
        return new VehDueWorkdayCalendar(days);
    }
}
