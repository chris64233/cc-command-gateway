package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;
import java.util.List;

import com.chris64233.cc.commandgateway.domain.CommandState;

public record CommandView(
        String commandUuid,
        String deviceId,
        String leaseId,
        long fenceToken,
        long clientSeq,
        String idempotencyKey,
        String payload,
        long acceptOrder,
        CommandState state,
        Instant acceptedAt,
        List<ReceiptView> receipts) {
}
