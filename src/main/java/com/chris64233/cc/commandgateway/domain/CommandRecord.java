package com.chris64233.cc.commandgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "device_commands",
        uniqueConstraints = @UniqueConstraint(columnNames = {"lease_id", "idempotency_key"}))
public class CommandRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "lease_id", nullable = false)
    private Long leaseId;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "sequence_no", nullable = false)
    private long sequence;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "payload", nullable = false, length = 8192)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CommandStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CommandRecord() {
    }

    public CommandRecord(Long deviceId, Long leaseId, long fencingToken, long sequence,
                         String idempotencyKey, String payload, Instant createdAt) {
        this.deviceId = deviceId;
        this.leaseId = leaseId;
        this.fencingToken = fencingToken;
        this.sequence = sequence;
        this.idempotencyKey = idempotencyKey;
        this.payload = payload;
        this.status = CommandStatus.ACCEPTED;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getLeaseId() {
        return leaseId;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public long getSequence() {
        return sequence;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayload() {
        return payload;
    }

    public CommandStatus getStatus() {
        return status;
    }

    public void setStatus(CommandStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
