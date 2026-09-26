package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

public record LeaseView(
        String leaseId,
        String deviceId,
        String clientId,
        long fenceToken,
        long lastAcceptedSeq,
        Instant acquiredAt,
        Instant expiresAt,
        boolean active) {
}
