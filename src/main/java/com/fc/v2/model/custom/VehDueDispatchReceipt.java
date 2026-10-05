package com.fc.v2.model.custom;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 催办派批次收口回执（一趟交三个数：还欠开口/这一趟派出/标成分不出，只报这一趟这一回）。
 *
 * 屏上看（plan，只看不动库）与真走一趟（runBatch）同一个出处：挑中哪几桩、窗口动不动手、
 * 递到谁名下、分不出怎么标，全照这一份回执回话。
 *
 * @author fuce
 * @date 2026-10-05
 */
@ApiModel(value = "VehDueDispatchReceipt", description = "催办派批次收口回执")
public class VehDueDispatchReceipt implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 桩的下落（派出/分不出/留在原地） */
    public static final String OUT_DONE = "派出";
    public static final String OUT_FAIL = "分不出";
    public static final String OUT_HOLD = "留在原地";

    @ApiModelProperty(value = "问话/触发那一刻（墙钟 yyyy-MM-dd HH:mm:ss）")
    private String moment;

    @ApiModelProperty(value = "那一刻在不在开口窗口 [02:00,05:00) 内")
    private boolean inWindow;

    @ApiModelProperty(value = "这一趟动没动库（窗口两头静默、时刻缺省都不动）")
    private boolean acted;

    @ApiModelProperty(value = "还欠开口几条（没到开口点的 + 超 500 留下的）")
    private int waitingCount;

    @ApiModelProperty(value = "这一趟递出去几条")
    private int dispatchedCount;

    @ApiModelProperty(value = "这一趟标成分不出几条")
    private int failCount;

    @ApiModelProperty(value = "挑中但超过五百上限留下的条数")
    private int leftoverCount;

    @ApiModelProperty(value = "这一趟挑中的桩及其下落（止点先到先办，同止点按代号）")
    private List<Item> items = new ArrayList<Item>();

    @ApiModelProperty(value = "超过五百上限、留到来年那一趟的桩代号")
    private List<String> leftovers = new ArrayList<String>();

    @ApiModelProperty(value = "收口一句话")
    private String message;

    /** 一桩的下落与留痕 */
    public static class Item implements Serializable {
        private static final long serialVersionUID = 1L;

        @ApiModelProperty(value = "条目代号")
        private String itemNo;

        @ApiModelProperty(value = "应开口止点时刻")
        private String dueAt;

        @ApiModelProperty(value = "所对业户代号")
        private String siteNo;

        @ApiModelProperty(value = "下落 派出/分不出")
        private String outcome;

        @ApiModelProperty(value = "钉到的经办人代号")
        private String handlerNo;

        @ApiModelProperty(value = "分不出卡在哪一环")
        private String failStage;

        @ApiModelProperty(value = "分不出缘由")
        private String failReason;

        public Item() {
        }

        public Item(String itemNo, String dueAt, String siteNo, String outcome,
                    String handlerNo, String failStage, String failReason) {
            this.itemNo = itemNo;
            this.dueAt = dueAt;
            this.siteNo = siteNo;
            this.outcome = outcome;
            this.handlerNo = handlerNo;
            this.failStage = failStage;
            this.failReason = failReason;
        }

        public String getItemNo() {
            return itemNo;
        }

        public void setItemNo(String itemNo) {
            this.itemNo = itemNo;
        }

        public String getDueAt() {
            return dueAt;
        }

        public void setDueAt(String dueAt) {
            this.dueAt = dueAt;
        }

        public String getSiteNo() {
            return siteNo;
        }

        public void setSiteNo(String siteNo) {
            this.siteNo = siteNo;
        }

        public String getOutcome() {
            return outcome;
        }

        public void setOutcome(String outcome) {
            this.outcome = outcome;
        }

        public String getHandlerNo() {
            return handlerNo;
        }

        public void setHandlerNo(String handlerNo) {
            this.handlerNo = handlerNo;
        }

        public String getFailStage() {
            return failStage;
        }

        public void setFailStage(String failStage) {
            this.failStage = failStage;
        }

        public String getFailReason() {
            return failReason;
        }

        public void setFailReason(String failReason) {
            this.failReason = failReason;
        }
    }

    public String getMoment() {
        return moment;
    }

    public void setMoment(String moment) {
        this.moment = moment;
    }

    public boolean isInWindow() {
        return inWindow;
    }

    public void setInWindow(boolean inWindow) {
        this.inWindow = inWindow;
    }

    public boolean isActed() {
        return acted;
    }

    public void setActed(boolean acted) {
        this.acted = acted;
    }

    public int getWaitingCount() {
        return waitingCount;
    }

    public void setWaitingCount(int waitingCount) {
        this.waitingCount = waitingCount;
    }

    public int getDispatchedCount() {
        return dispatchedCount;
    }

    public void setDispatchedCount(int dispatchedCount) {
        this.dispatchedCount = dispatchedCount;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public int getLeftoverCount() {
        return leftoverCount;
    }

    public void setLeftoverCount(int leftoverCount) {
        this.leftoverCount = leftoverCount;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    public List<String> getLeftovers() {
        return leftovers;
    }

    public void setLeftovers(List<String> leftovers) {
        this.leftovers = leftovers;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
