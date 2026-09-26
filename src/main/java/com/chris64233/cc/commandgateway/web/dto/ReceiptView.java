package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

import com.chris64233.cc.commandgateway.domain.ReceiptKind;

public record ReceiptView(
        String eventId,
        String commandUuid,
        long fenceToken,
        ReceiptKind kind,
        String content,
        Instant receivedAt) {
}
