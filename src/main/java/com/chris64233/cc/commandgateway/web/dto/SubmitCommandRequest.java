package com.chris64233.cc.commandgateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SubmitCommandRequest(
        @NotBlank
        @Size(max = 64)
        String leaseId,

        @Positive
        long fenceToken,

        @Positive
        long clientSeq,

        @NotBlank
        @Size(max = 128)
        String idempotencyKey,

        @NotNull
        String payload) {
}
