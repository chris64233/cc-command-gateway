package com.chris64233.cc.commandgateway.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterDeviceRequest(@NotBlank String deviceNumber) {
}
