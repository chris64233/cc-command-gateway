package com.chris64233.cc.commandgateway.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "command",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_command_device_idem",
                        columnNames = {"device_id", "idempotency_key"}),
                @UniqueConstraint(name = "uk_command_lease_seq",
                        columnNames = {"lease_id", "client_seq"})
        },
        indexes = @Index(name = "idx_command_device_order",
                columnList = "device_id, accept_order"))
public class CommandRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_uuid", nullable = false, length = 64)
    private String commandUuid;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "lease_id", nullable = false, length = 64)
    private String leaseId;

    @Column(name = "fence_token", nullable = false)
    private long fenceToken;

    @Column(name = "client_seq", nullable = false)
    private long clientSeq;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "accept_order", nullable = false)
    private long acceptOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private CommandState state;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    protected CommandRecord() {
    }

    public CommandRecord(String commandUuid, String deviceId, String leaseId, long fenceToken,
                         long clientSeq, String idempotencyKey, String payload,
                         long acceptOrder, Instant acceptedAt, Instant deadlineAt) {
        this.commandUuid = commandUuid;
        this.deviceId = deviceId;
        this.leaseId = leaseId;
        this.fenceToken = fenceToken;
        this.clientSeq = clientSeq;
        this.idempotencyKey = idempotencyKey;
        this.payload = payload;
        this.acceptOrder = acceptOrder;
        this.state = CommandState.PENDING;
        this.acceptedAt = acceptedAt;
        this.deadlineAt = deadlineAt;
    }

    public Long getId() {
        return id;
    }

    public String getCommandUuid() {
        return commandUuid;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getLeaseId() {
        return leaseId;
    }

    public long getFenceToken() {
        return fenceToken;
    }

    public long getClientSeq() {
        return clientSeq;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPayload() {
        return payload;
    }

    public long getAcceptOrder() {
        return acceptOrder;
    }

    public CommandState getState() {
        return state;
    }

    public void setState(CommandState state) {
        this.state = state;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }
}
