package com.chris64233.cc.commandgateway.web.dto;

import com.chris64233.cc.commandgateway.domain.Lease;

import java.time.Instant;

public record LeaseView(
        long id,
        String clientId,
        long fencingToken,
        Instant createdAt,
        Instant expiresAt,
        long lastAcceptedSequence,
        boolean active) {

    public static LeaseView from(Lease lease, Instant now) {
        return new LeaseView(lease.getId(), lease.getClientId(), lease.getFencingToken(),
                lease.getCreatedAt(), lease.getExpiresAt(), lease.getLastAcceptedSequence(),
                lease.isActiveAt(now));
    }
}
