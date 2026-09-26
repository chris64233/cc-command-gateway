package com.chris64233.cc.commandgateway.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "lease",
        uniqueConstraints = @UniqueConstraint(name = "uk_lease_id", columnNames = "lease_id"),
        indexes = @Index(name = "idx_lease_device", columnList = "device_id"))
public class Lease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lease_id", nullable = false, length = 64)
    private String leaseId;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    @Column(name = "fence_token", nullable = false)
    private long fenceToken;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_accepted_seq", nullable = false)
    private long lastAcceptedSeq;

    protected Lease() {
    }

    public Lease(String leaseId, String deviceId, String clientId, long fenceToken,
                 Instant acquiredAt, Instant expiresAt) {
        this.leaseId = leaseId;
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.fenceToken = fenceToken;
        this.acquiredAt = acquiredAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public String getLeaseId() {
        return leaseId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getClientId() {
        return clientId;
    }

    public long getFenceToken() {
        return fenceToken;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getLastAcceptedSeq() {
        return lastAcceptedSeq;
    }

    public void setLastAcceptedSeq(long lastAcceptedSeq) {
        this.lastAcceptedSeq = lastAcceptedSeq;
    }
}
