package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

public record DeviceView(
        String deviceId,
        Instant createdAt) {
}
