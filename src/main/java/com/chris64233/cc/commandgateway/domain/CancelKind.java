package com.chris64233.cc.commandgateway.domain;

public enum CancelKind {
    /** 客户端主动取消。 */
    CLIENT,
    /** 超过截止时间，由超时扫描取消。 */
    TIMEOUT;
}
