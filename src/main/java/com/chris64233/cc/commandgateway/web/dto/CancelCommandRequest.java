package com.chris64233.cc.commandgateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CancelCommandRequest(
        @NotBlank
        @Size(max = 64)
        String leaseId,

        @Positive
        long fenceToken,

        @NotBlank
        @Size(max = 64)
        String cancelId,

        @Size(max = 512)
        String reason) {
}
