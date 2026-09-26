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
        name = "receipt_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_receipt_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_receipt_command", columnList = "command_id"))
public class ReceiptEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "command_id", nullable = false)
    private Long commandId;

    @Column(name = "command_uuid", nullable = false, length = 64)
    private String commandUuid;

    @Column(name = "fence_token", nullable = false)
    private long fenceToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private ReceiptKind kind;

    @Lob
    @Column(name = "content")
    private String content;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected ReceiptEvent() {
    }

    public ReceiptEvent(String eventId, Long commandId, String commandUuid, long fenceToken,
                        ReceiptKind kind, String content, Instant receivedAt) {
        this.eventId = eventId;
        this.commandId = commandId;
        this.commandUuid = commandUuid;
        this.fenceToken = fenceToken;
        this.kind = kind;
        this.content = content;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
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

    public ReceiptKind getKind() {
        return kind;
    }

    public String getContent() {
        return content;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
