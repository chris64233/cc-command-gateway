package com.chris64233.cc.commandgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "leases")
public class Lease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_accepted_sequence", nullable = false)
    private long lastAcceptedSequence;

    protected Lease() {
    }

    public Lease(Long deviceId, String clientId, long fencingToken, Instant createdAt, Instant expiresAt) {
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.fencingToken = fencingToken;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastAcceptedSequence = 0;
    }

    public boolean isActiveAt(Instant instant) {
        return expiresAt.isAfter(instant);
    }

    public Long getId() {
        return id;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getClientId() {
        return clientId;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getLastAcceptedSequence() {
        return lastAcceptedSequence;
    }

    public void setLastAcceptedSequence(long lastAcceptedSequence) {
        this.lastAcceptedSequence = lastAcceptedSequence;
    }
}
