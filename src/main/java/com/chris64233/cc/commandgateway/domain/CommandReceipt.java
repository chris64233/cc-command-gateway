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
@Table(name = "command_receipts",
        uniqueConstraints = @UniqueConstraint(columnNames = {"command_id", "event_id"}))
public class CommandReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false)
    private Long commandId;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CommandStatus status;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected CommandReceipt() {
    }

    public CommandReceipt(Long commandId, String eventId, long fencingToken,
                          CommandStatus status, String detail, Instant receivedAt) {
        this.commandId = commandId;
        this.eventId = eventId;
        this.fencingToken = fencingToken;
        this.status = status;
        this.detail = detail;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCommandId() {
        return commandId;
    }

    public String getEventId() {
        return eventId;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public CommandStatus getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
