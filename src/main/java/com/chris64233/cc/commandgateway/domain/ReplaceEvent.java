package com.chris64233.cc.commandgateway.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 指令替换事件：一条旧指令被一条新指令完整取代。
 * 旧指令置为 {@link CommandState#REPLACED} 并保留可追溯结束状态，
 * 新指令作为正常指令接受，二者关系与时间线在同一事务内提交。
 */
@Entity
@Table(
        name = "replace_event",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_replace_event_id", columnNames = "replace_id"),
                @UniqueConstraint(name = "uk_replace_event_old_command", columnNames = "old_command_id")
        },
        indexes = {
                @Index(name = "idx_replace_old_command", columnList = "old_command_id"),
                @Index(name = "idx_replace_new_command", columnList = "new_command_id")
        })
public class ReplaceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 外部请求号，保证替换请求幂等。 */
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

    /** 执行替换时使用的租约与栅栏令牌（新指令在此上下文下接受）。 */
    @Column(name = "lease_id", nullable = false, length = 64)
    private String leaseId;

    @Column(name = "fence_token", nullable = false)
    private long fenceToken;

    @Column(name = "client_seq", nullable = false)
    private long clientSeq;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /** 新指令请求内容，用于同号不同内容的冲突判定。 */
    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    /** 新指令最终生效的截止时间（请求缺省时继承旧指令）。 */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "replaced_at", nullable = false)
    private Instant replacedAt;

    protected ReplaceEvent() {
    }

    public ReplaceEvent(String replaceId, String deviceId,
                        Long oldCommandId, String oldCommandUuid,
                        Long newCommandId, String newCommandUuid,
                        String leaseId, long fenceToken, long clientSeq, String idempotencyKey,
                        String payload, Instant deadlineAt, String reason, Instant replacedAt) {
        this.replaceId = replaceId;
        this.deviceId = deviceId;
        this.oldCommandId = oldCommandId;
        this.oldCommandUuid = oldCommandUuid;
        this.newCommandId = newCommandId;
        this.newCommandUuid = newCommandUuid;
        this.leaseId = leaseId;
        this.fenceToken = fenceToken;
        this.clientSeq = clientSeq;
        this.idempotencyKey = idempotencyKey;
        this.payload = payload;
        this.deadlineAt = deadlineAt;
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

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public String getReason() {
        return reason;
    }

    public Instant getReplacedAt() {
        return replacedAt;
    }
}
