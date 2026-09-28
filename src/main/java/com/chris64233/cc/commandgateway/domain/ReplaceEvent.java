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

/**
 * 替换事件：一条旧命令被一条新命令完整取代的关系与时间线。
 * 与旧命令进入 REPLACED、新命令落库在同一事务内提交，不删除旧命令。
 */
@Entity
@Table(
        name = "replace_event",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_replace_event_id", columnNames = "replace_id"),
                @UniqueConstraint(name = "uk_replace_event_old", columnNames = "old_command_id"),
                @UniqueConstraint(name = "uk_replace_event_new", columnNames = "new_command_id")
        },
        indexes = {
                @Index(name = "idx_replace_old", columnList = "old_command_id"),
                @Index(name = "idx_replace_new", columnList = "new_command_id")
        })
public class ReplaceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 外部替换请求号，保证替换操作幂等。 */
    @Column(name = "replace_id", nullable = false, length = 128)
    private String replaceId;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "old_command_id", nullable = false)
    private Long oldCommandId;

    @Column(name = "old_command_uuid", nullable = false, length = 64)
    private String oldCommandUuid;

    @Column(name = "new_command_id", nullable = false)
    private Long newCommandId;

    @Column(name = "new_command_uuid", nullable = false, length = 64)
    private String newCommandUuid;

    @Column(name = "fence_token", nullable = false)
    private long fenceToken;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "replaced_at", nullable = false)
    private Instant replacedAt;

    protected ReplaceEvent() {
    }

    public ReplaceEvent(String replaceId, String deviceId,
                        Long oldCommandId, String oldCommandUuid,
                        Long newCommandId, String newCommandUuid,
                        long fenceToken, String reason, Instant replacedAt) {
        this.replaceId = replaceId;
        this.deviceId = deviceId;
        this.oldCommandId = oldCommandId;
        this.oldCommandUuid = oldCommandUuid;
        this.newCommandId = newCommandId;
        this.newCommandUuid = newCommandUuid;
        this.fenceToken = fenceToken;
        this.reason = reason;
        this.replacedAt = replacedAt;
    }

    public Long getId() {
        return id;
    }

    public String getReplaceId() {
        return replaceId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Long getOldCommandId() {
        return oldCommandId;
    }

    public String getOldCommandUuid() {
        return oldCommandUuid;
    }

    public Long getNewCommandId() {
        return newCommandId;
    }

    public String getNewCommandUuid() {
        return newCommandUuid;
    }

    public long getFenceToken() {
        return fenceToken;
    }

    public String getReason() {
        return reason;
    }

    public Instant getReplacedAt() {
        return replacedAt;
    }
}
