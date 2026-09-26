package com.chris64233.cc.commandgateway.domain;

public enum CommandStatus {
    ACCEPTED,
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
