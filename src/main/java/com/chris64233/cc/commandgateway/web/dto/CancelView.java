package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

import com.chris64233.cc.commandgateway.domain.CancelKind;

public record CancelView(
        String cancelId,
        String commandUuid,
        long fenceToken,
        CancelKind kind,
        String reason,
        Instant cancelledAt) {
}
