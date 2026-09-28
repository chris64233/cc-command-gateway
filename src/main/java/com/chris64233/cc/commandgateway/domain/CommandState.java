package com.chris64233.cc.commandgateway.domain;

public enum CommandState {
    /** 已接受，等待派发到设备。 */
    PENDING,
    /** 已派发，等待设备回执。 */
    DISPATCHED,
    /** 设备已开始执行（收到 ACK）。 */
    ACKNOWLEDGED,
    SUCCEEDED,
    FAILED,
    /** 客户端主动取消。 */
    CANCELLED,
    /** 超过截止时间，由超时扫描取消。 */
    TIMED_OUT,
    /** 尚未进入设备执行阶段时被新命令完整取代，新命令见 replacement 关系。 */
    REPLACED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED
                || this == CANCELLED || this == TIMED_OUT || this == REPLACED;
    }

    /** 尚未进入设备执行阶段（未收到 ACK），允许取消。 */
    public boolean isCancellable() {
        return this == PENDING || this == DISPATCHED;
    }

    /** 取消类终态：迟到回执作为异常记录保留，但不得改变状态。 */
    public boolean isCancelled() {
        return this == CANCELLED || this == TIMED_OUT;
    }

    /**
     * 命令在尚未派发到设备执行前可被新命令完整取代：与取消相同的状态范围，
     * 即 PENDING / DISPATCHED。一旦 ACKNOWLEDGED 或进入终态即不可替换。
     */
    public boolean isReplaceable() {
        return this == PENDING || this == DISPATCHED;
    }

    /** 设备不会执行的指令终态（取消 / 超时 / 被替换）：迟到回执仅作异常保留。 */
    public boolean isAborted() {
        return isCancelled() || this == REPLACED;
    }
}
