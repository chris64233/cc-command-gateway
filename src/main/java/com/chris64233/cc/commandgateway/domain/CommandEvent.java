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

/**
 * 指令生命周期事件（接受、派发、取消、超时）。事件编号全局唯一：
 * 取消事件使用客户端提供的取消号，其余事件使用确定性编号，
 * 使取消与超时扫描重试天然幂等。
 */
@Entity
@Table(
        name = "command_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_command_event_id", columnNames = "event_id"),
        indexes = @Index(name = "idx_command_event_command", columnList = "command_id"))
public class CommandEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "command_id", nullable = false)
    private Long commandId;

    @Column(name = "command_uuid", nullable = false, length = 64)
    private String commandUuid;

    @Column(name = "fence_token", nullable = false)
    private long fenceToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private CommandEventKind kind;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected CommandEvent() {
    }

    public CommandEvent(String eventId, Long commandId, String commandUuid, long fenceToken,
                        CommandEventKind kind, String detail, Instant occurredAt) {
        this.eventId = eventId;
        this.commandId = commandId;
        this.commandUuid = commandUuid;
        this.fenceToken = fenceToken;
        this.kind = kind;
        this.detail = detail;
        this.occurredAt = occurredAt;
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

    public CommandEventKind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
