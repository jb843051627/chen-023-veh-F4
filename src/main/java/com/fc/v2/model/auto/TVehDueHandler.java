package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 催办经办人花名册对象 t_veh_due_handler
 *
 * 这一季谁在岗、定额多少、手上已接几桩、哪类业户接得过来，都在行上；
 * 派桩按 active_count 少者先接，休办/类别接不来/同户已有人/塞满定额依次顺延。
 *
 * @author fuce
 * @date 2026-10-05
 */
@TableName("t_veh_due_handler")
@ApiModel(value = "TVehDueHandler", description = "催办经办人花名册")
public class TVehDueHandler implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 经办人代号 */
    @TableField("handler_no")
    @ApiModelProperty(value = "经办人代号")
    private String handlerNo;

    /** 经办人姓名 */
    @TableField("handler_name")
    @ApiModelProperty(value = "经办人姓名")
    private String handlerName;

    /** 这一季休办 0在岗 1休办 */
    @TableField("leave_flag")
    @ApiModelProperty(value = "这一季休办 0在岗 1休办")
    private Integer leaveFlag;

    /** 这一季手上定额（空着按不设顶认） */
    @TableField("quota")
    @ApiModelProperty(value = "这一季手上定额")
    private Integer quota;

    /** 这一季手上已接着的活 */
    @TableField("active_count")
    @ApiModelProperty(value = "这一季手上已接着的活")
    private Integer activeCount;

    /** 接得过来的业户类别（字典序逗号隔开；空着=哪类都接） */
    @TableField("accept_types")
    @ApiModelProperty(value = "接得过来的业户类别（逗号隔开，空着全接）")
    private String acceptTypes;

    /** 经办人情形 0可用 1停用 */
    @TableField("status")
    @ApiModelProperty(value = "经办人情形 0可用 1停用")
    private Integer status;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    /** 更新者 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /** 备注 */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHandlerNo() {
        return handlerNo;
    }

    public void setHandlerNo(String handlerNo) {
        this.handlerNo = handlerNo;
    }

    public String getHandlerName() {
        return handlerName;
    }

    public void setHandlerName(String handlerName) {
        this.handlerName = handlerName;
    }

    public Integer getLeaveFlag() {
        return leaveFlag;
    }

    public void setLeaveFlag(Integer leaveFlag) {
        this.leaveFlag = leaveFlag;
    }

    public Integer getQuota() {
        return quota;
    }

    public void setQuota(Integer quota) {
        this.quota = quota;
    }

    public Integer getActiveCount() {
        return activeCount;
    }

    public void setActiveCount(Integer activeCount) {
        this.activeCount = activeCount;
    }

    public String getAcceptTypes() {
        return acceptTypes;
    }

    public void setAcceptTypes(String acceptTypes) {
        this.acceptTypes = acceptTypes;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
