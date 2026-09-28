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
        Instant deadlineAt,
        Instant dispatchedAt,
        CancelView cancel,
        List<ReceiptView> receipts,
        /** 本指令被哪条新指令取代（仅 REPLACED 状态非空）。 */
        ReplacementView replacedBy,
        /** 本指令取代了哪条旧指令（仅作为替换产物接受时非空）。 */
        ReplacementView replacementOf) {
}
