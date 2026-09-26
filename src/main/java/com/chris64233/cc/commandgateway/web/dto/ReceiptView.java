package com.chris64233.cc.commandgateway.web.dto;

import com.chris64233.cc.commandgateway.domain.CommandReceipt;
import com.chris64233.cc.commandgateway.domain.CommandStatus;

import java.time.Instant;

public record ReceiptView(
        long id,
        long commandId,
        String eventId,
        CommandStatus status,
        String detail,
        Instant receivedAt) {

    public static ReceiptView from(CommandReceipt receipt) {
        return new ReceiptView(receipt.getId(), receipt.getCommandId(), receipt.getEventId(),
                receipt.getStatus(), receipt.getDetail(), receipt.getReceivedAt());
    }
}
