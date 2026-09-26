package com.chris64233.cc.commandgateway.domain;

public enum ReceiptKind {
    ACK,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
