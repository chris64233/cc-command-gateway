package com.chris64233.cc.commandgateway.domain;

public enum CommandState {
    PENDING,
    ACKNOWLEDGED,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
