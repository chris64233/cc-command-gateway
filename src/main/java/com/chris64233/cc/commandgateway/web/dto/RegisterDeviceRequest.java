package com.chris64233.cc.commandgateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterDeviceRequest(
        @NotBlank
        @Size(max = 128)
        String deviceId) {
}
