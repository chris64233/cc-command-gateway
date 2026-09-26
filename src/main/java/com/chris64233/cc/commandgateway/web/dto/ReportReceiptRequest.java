package com.chris64233.cc.commandgateway.web.dto;

import com.chris64233.cc.commandgateway.domain.ReceiptKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReportReceiptRequest(
        @NotBlank
        @Size(max = 64)
        String commandUuid,

        @NotBlank
        @Size(max = 64)
        String leaseId,

        @Positive
        long fenceToken,

        @NotBlank
        @Size(max = 64)
        String eventId,

        @NotNull
        ReceiptKind kind,

        String content) {
}
