package com.chris64233.cc.commandgateway.web.dto;

import com.chris64233.cc.commandgateway.domain.CommandStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReceiptRequest(
        @NotBlank String eventId,
        @Positive long fencingToken,
        @NotNull CommandStatus status,
        String detail) {
}
