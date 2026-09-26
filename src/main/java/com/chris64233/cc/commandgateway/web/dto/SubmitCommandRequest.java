package com.chris64233.cc.commandgateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SubmitCommandRequest(
        @NotNull Long leaseId,
        @Positive long fencingToken,
        @Positive long sequence,
        @NotBlank String idempotencyKey,
        @NotNull String payload) {
}
