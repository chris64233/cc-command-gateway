package com.chris64233.cc.commandgateway.domain;

public enum CommandEventKind {
    /** 指令被接受。 */
    ACCEPTED,
    /** 指令被派发到设备。 */
    DISPATCHED,
    /** 客户端主动取消。 */
    CANCELLED,
    /** 超过截止时间，由超时扫描取消。 */
    TIMED_OUT
}
