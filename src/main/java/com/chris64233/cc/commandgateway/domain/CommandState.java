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
    TIMED_OUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    /** 尚未进入设备执行阶段（未收到 ACK），允许取消。 */
    public boolean isCancellable() {
        return this == PENDING || this == DISPATCHED;
    }

    /** 取消类终态：迟到回执作为异常记录保留，但不得改变状态。 */
    public boolean isCancelled() {
        return this == CANCELLED || this == TIMED_OUT;
    }
}
