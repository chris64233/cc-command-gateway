package com.chris64233.cc.commandgateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AcquireLeaseRequest(@NotBlank String clientId, @NotNull @Positive Long ttlMillis) {
}
