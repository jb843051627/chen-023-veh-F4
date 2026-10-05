package com.fc.v2.model.custom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 证照到期批派一趟的回执。
 *
 * 一趟收口只交三个数：还欠开口几条、这一趟递出去几条、标成递不动几条——
 * 只报这一趟这一回，不把历史并进来；三个数回库逐条点过才许出口。
 * 窗口两头静默段按下触发：不动库、不改一格，windowOpen=false、三个数留空（不是 0）。
 *
 * @author fuce
 * @date 2026-10-05
 */
@ApiModel(value = "VehDueDispatchReceipt", description = "证照到期批派一趟回执")
public class VehDueDispatchReceipt implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 触发那一刻在不在开口窗口内（静默段只应声不动库） */
    @ApiModelProperty(value = "触发那一刻在不在开口窗口内")
    private Boolean windowOpen;

    /** 收口批注：窗口静默/这一趟没有到期的/超顶留下几桩等说法都在这一句 */
    @ApiModelProperty(value = "收口批注")
    private String mark;

    /** 还欠开口几条（收口时仍搁着的，回库现点；未开口与超顶留下的都在内） */
    @ApiModelProperty(value = "还欠开口几条")
    private Integer pendingCount;

    /** 这一趟递出去几条（回库逐条点过） */
    @ApiModelProperty(value = "这一趟递出去几条")
    private Integer dispatchedCount;

    /** 这一趟标成递不动几条（回库逐条点过） */
    @ApiModelProperty(value = "这一趟标成递不动几条")
    private Integer failedCount;

    /** 到期挑中几条（截断前） */
    @ApiModelProperty(value = "到期挑中几条（截断前）")
    private Integer pickedCount;

    /** 一趟只办一批的顶 */
    @ApiModelProperty(value = "一趟只办一批的顶")
    private Integer batchCap;

    /** 挑中多过顶时余下几桩（留到来年那一趟） */
    @ApiModelProperty(value = "超顶留下几桩")
    private Integer leftToNext;

    /** 本桩逐条结果（挑中哪些、递到谁名下、递不动卡在哪一环，四问四答只认这一份） */
    @ApiModelProperty(value = "本桩逐条结果")
    private List<ItemResult> items = new ArrayList<ItemResult>();

    /** 一桩一条的结果 */
    @ApiModel(value = "VehDueDispatchReceipt.ItemResult")
    public static class ItemResult implements Serializable {
        private static final long serialVersionUID = 1L;

        @ApiModelProperty(value = "行主键（回库逐条点对的就是它）")
        private Long id;

        @ApiModelProperty(value = "条目代号")
        private String itemNo;

        /** 1递出去了 2递不动 */
        @ApiModelProperty(value = "结果 1递出去了 2递不动")
        private Integer result;

        @ApiModelProperty(value = "递到谁名下")
        private String assigneeNo;

        @ApiModelProperty(value = "递出去那一刻")
        private String dispatchedAt;

        @ApiModelProperty(value = "卡在哪一环")
        private String blockStage;

        @ApiModelProperty(value = "缘由")
        private String blockReason;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getItemNo() {
            return itemNo;
        }

        public void setItemNo(String itemNo) {
            this.itemNo = itemNo;
        }

        public Integer getResult() {
            return result;
        }

        public void setResult(Integer result) {
            this.result = result;
        }

        public String getAssigneeNo() {
            return assigneeNo;
        }

        public void setAssigneeNo(String assigneeNo) {
            this.assigneeNo = assigneeNo;
        }

        public String getDispatchedAt() {
            return dispatchedAt;
        }

        public void setDispatchedAt(String dispatchedAt) {
            this.dispatchedAt = dispatchedAt;
        }

        public String getBlockStage() {
            return blockStage;
        }

        public void setBlockStage(String blockStage) {
            this.blockStage = blockStage;
        }

        public String getBlockReason() {
            return blockReason;
        }

        public void setBlockReason(String blockReason) {
            this.blockReason = blockReason;
        }
    }

    public Boolean getWindowOpen() {
        return windowOpen;
    }

    public void setWindowOpen(Boolean windowOpen) {
        this.windowOpen = windowOpen;
    }

    public String getMark() {
        return mark;
    }

    public void setMark(String mark) {
        this.mark = mark;
    }

    public Integer getPendingCount() {
        return pendingCount;
    }

    public void setPendingCount(Integer pendingCount) {
        this.pendingCount = pendingCount;
    }

    public Integer getDispatchedCount() {
        return dispatchedCount;
    }

    public void setDispatchedCount(Integer dispatchedCount) {
        this.dispatchedCount = dispatchedCount;
    }

    public Integer getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(Integer failedCount) {
        this.failedCount = failedCount;
    }

    public Integer getPickedCount() {
        return pickedCount;
    }

    public void setPickedCount(Integer pickedCount) {
        this.pickedCount = pickedCount;
    }

    public Integer getBatchCap() {
        return batchCap;
    }

    public void setBatchCap(Integer batchCap) {
        this.batchCap = batchCap;
    }

    public Integer getLeftToNext() {
        return leftToNext;
    }

    public void setLeftToNext(Integer leftToNext) {
        this.leftToNext = leftToNext;
    }

    public List<ItemResult> getItems() {
        return items;
    }

    public void setItems(List<ItemResult> items) {
        this.items = items;
    }
}
