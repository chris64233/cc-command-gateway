package com.chris64233.cc.commandgateway.domain;

public enum CommandState {
    /** 待派发：已接受，尚未下发到设备。 */
    PENDING,
    /** 已派发：已下发到设备，等待设备开始执行回执。 */
    DISPATCHED,
    /** 设备已开始执行。 */
    ACKNOWLEDGED,
    SUCCEEDED,
    FAILED,
    /** 客户端主动取消。 */
    CANCELLED,
    /** 超过指令截止时间，由超时扫描取消。 */
    TIMED_OUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    /** 尚未进入设备执行阶段，允许取消或超时。 */
    public boolean isCancellable() {
        return this == PENDING || this == DISPATCHED;
    }
}
