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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "cancel_event",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_cancel_event_id", columnNames = "cancel_id"),
                @UniqueConstraint(name = "uk_cancel_event_command", columnNames = "command_id")
        },
        indexes = @Index(name = "idx_cancel_command", columnList = "command_id"))
public class CancelEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cancel_id", nullable = false, length = 128)
    private String cancelId;

    @Column(name = "command_id", nullable = false)
    private Long commandId;

    @Column(name = "command_uuid", nullable = false, length = 64)
    private String commandUuid;

    @Column(name = "fence_token", nullable = false)
    private long fenceToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private CancelKind kind;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt;

    protected CancelEvent() {
    }

    public CancelEvent(String cancelId, Long commandId, String commandUuid, long fenceToken,
                       CancelKind kind, String reason, Instant cancelledAt) {
        this.cancelId = cancelId;
        this.commandId = commandId;
        this.commandUuid = commandUuid;
        this.fenceToken = fenceToken;
        this.kind = kind;
        this.reason = reason;
        this.cancelledAt = cancelledAt;
    }

    public Long getId() {
        return id;
    }

    public String getCancelId() {
        return cancelId;
    }

    public Long getCommandId() {
        return commandId;
    }

    public String getCommandUuid() {
        return commandUuid;
    }

    public long getFenceToken() {
        return fenceToken;
    }

    public CancelKind getKind() {
        return kind;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
