package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AcquireLeaseRequest(
        @NotBlank
        @Size(max = 128)
        String clientId,

        @NotNull
        @Future
        Instant expiresAt) {
}
