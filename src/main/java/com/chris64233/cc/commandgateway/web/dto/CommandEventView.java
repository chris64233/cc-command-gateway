package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

import com.chris64233.cc.commandgateway.domain.CommandEventKind;

public record CommandEventView(
        String eventId,
        CommandEventKind kind,
        long fenceToken,
        String detail,
        Instant occurredAt) {
}
