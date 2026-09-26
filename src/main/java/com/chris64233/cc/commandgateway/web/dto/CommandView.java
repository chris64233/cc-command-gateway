package com.chris64233.cc.commandgateway.web.dto;

import com.chris64233.cc.commandgateway.domain.CommandRecord;
import com.chris64233.cc.commandgateway.domain.CommandReceipt;
import com.chris64233.cc.commandgateway.domain.CommandStatus;

import java.time.Instant;
import java.util.List;

public record CommandView(
        long id,
        long leaseId,
        long fencingToken,
        long sequence,
        String idempotencyKey,
        String payload,
        CommandStatus status,
        Instant createdAt,
        List<ReceiptView> receipts) {

    public static CommandView from(CommandRecord command, List<CommandReceipt> receipts) {
        return new CommandView(command.getId(), command.getLeaseId(), command.getFencingToken(),
                command.getSequence(), command.getIdempotencyKey(), command.getPayload(),
                command.getStatus(), command.getCreatedAt(),
                receipts.stream().map(ReceiptView::from).toList());
    }
}
