package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.commandgateway.domain.CommandRecord;
import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.domain.Device;
import com.chris64233.cc.commandgateway.domain.Lease;
import com.chris64233.cc.commandgateway.domain.ReplaceEvent;
import com.chris64233.cc.commandgateway.repo.CommandRepository;
import com.chris64233.cc.commandgateway.repo.DeviceRepository;
import com.chris64233.cc.commandgateway.repo.LeaseRepository;
import com.chris64233.cc.commandgateway.repo.ReplaceEventRepository;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.ReplaceResultView;
import com.chris64233.cc.commandgateway.web.dto.ReplacementView;

/**
 * 指令替换：允许尚未进入设备执行阶段的指令被新指令完整取代。
 *
 * <p>旧指令进入 {@link CommandState#REPLACED} 终态并保留可追溯状态；
 * 新指令作为正常指令接受，继承旧指令的设备上下文，并在同一租约业务上下文下
 * 获得新的 clientSeq、幂等键与接受顺序。旧新关系（{@link ReplaceEvent}）、
 * 旧指令终态与新指令在同一事务内提交。</p>
 */
@Service
public class ReplaceService {

    private final DeviceRepository deviceRepository;
    private final LeaseRepository leaseRepository;
    private final CommandRepository commandRepository;
    private final ReplaceEventRepository replaceEventRepository;
    private final Clock clock;

    public ReplaceService(DeviceRepository deviceRepository,
                          LeaseRepository leaseRepository,
                          CommandRepository commandRepository,
                          ReplaceEventRepository replaceEventRepository,
                          Clock clock) {
        this.deviceRepository = deviceRepository;
        this.leaseRepository = leaseRepository;
        this.commandRepository = commandRepository;
        this.replaceEventRepository = replaceEventRepository;
        this.clock = clock;
    }

    /**
     * 用新指令完整取代旧指令。允许替换的状态范围：{@code PENDING} / {@code DISPATCHED}
     * （尚未收到设备 ACK）。与设备开始回执、取消、超时扫描在同一指令行锁上竞争，
     * 只会形成一种结果。
     */
    @Transactional
    public ReplaceResultView replace(String deviceId, String oldCommandUuid, String leaseId,
                                     long fenceToken, long clientSeq, String idempotencyKey,
                                     String payload, Instant deadlineAt,
                                     String replaceId, String reason) {
        Instant now = Instant.now(clock);

        // 先锁设备行再锁旧指令行：与提交/取消使用一致的加锁顺序，避免死锁，
        // 并串行化 acceptOrder 与租约序号的推进。
        Device device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));

        CommandRecord oldCommand = commandRepository.findWithLockingByCommandUuid(oldCommandUuid)
                .orElseThrow(() -> ApiException.notFound("unknown command: " + oldCommandUuid));
        if (!oldCommand.getDeviceId().equals(deviceId)) {
            throw ApiException.notFound("unknown command: " + oldCommandUuid);
        }

        Lease lease = leaseRepository.findByLeaseId(leaseId)
                .orElseThrow(() -> ApiException.conflict("invalid_lease", "unknown lease: " + leaseId));
        if (!lease.getDeviceId().equals(deviceId)) {
            throw ApiException.conflict("invalid_lease",
                    "lease does not belong to device: " + deviceId);
        }
        if (fenceToken != lease.getFenceToken()) {
            throw ApiException.conflict("fence_token_mismatch",
                    "fence token does not match lease token");
        }
        if (fenceToken != device.getFenceToken() || fenceToken < oldCommand.getFenceToken()) {
            throw ApiException.conflict("stale_fence_token",
                    "fence token is not the current token of the device"
                            + " or is older than the command token");
        }
        if (lease.isExpiredAt(now)) {
            throw ApiException.conflict("lease_expired", "lease has expired");
        }

        // 外部请求号幂等：同号必须绑定同一条旧指令且请求内容完全一致。
        ReplaceEvent prior = replaceEventRepository.findByReplaceId(replaceId).orElse(null);
        if (prior != null) {
            if (!prior.getOldCommandId().equals(oldCommand.getId())) {
                throw ApiException.conflict("replace_event_conflict",
                        "replace id was already used for a different command: " + replaceId);
            }
            Instant effectiveDeadline = deadlineAt != null ? deadlineAt : oldCommand.getDeadlineAt();
            if (!sameReplaceRequest(prior, leaseId, fenceToken, clientSeq, idempotencyKey,
                    payload, effectiveDeadline, reason)) {
                throw ApiException.conflict("replace_event_conflict",
                        "replace id was already used with a different request: " + replaceId);
            }
            return replayResult(prior);
        }

        if (!oldCommand.getState().isReplaceable()) {
            // 设备已开始执行（ACK）或已到终态（成功/失败/取消/超时/已替换）均不可替换。
            throw ApiException.conflict("command_not_replaceable",
                    "command cannot be replaced from state: " + oldCommand.getState());
        }

        if (clientSeq <= lease.getLastAcceptedSeq()) {
            throw ApiException.conflict("stale_client_seq",
                    "clientSeq must be greater than the last accepted sequence: "
                            + lease.getLastAcceptedSeq());
        }

        // 新指令使用自身的幂等键；同一 (device, key) 已存在则冲突，绝不复用旧指令的键。
        commandRepository.findByDeviceIdAndIdempotencyKey(deviceId, idempotencyKey)
                .ifPresent(existing -> {
                    throw ApiException.conflict("idempotency_conflict",
                            "idempotency key was already used with a different request: "
                                    + idempotencyKey);
                });

        // 截止时间缺省继承旧指令的业务上下文。
        Instant effectiveDeadline = deadlineAt != null ? deadlineAt : oldCommand.getDeadlineAt();

        long acceptOrder = device.getNextAcceptOrder();
        device.advanceAcceptOrder();
        lease.setLastAcceptedSeq(clientSeq);

        CommandRecord newCommand = new CommandRecord(
                UUID.randomUUID().toString(),
                deviceId,
                leaseId,
                fenceToken,
                clientSeq,
                idempotencyKey,
                payload,
                acceptOrder,
                now,
                effectiveDeadline);
        commandRepository.save(newCommand);

        oldCommand.setState(CommandState.REPLACED);

        ReplaceEvent event = new ReplaceEvent(
                replaceId,
                deviceId,
                oldCommand.getId(),
                oldCommand.getCommandUuid(),
                newCommand.getId(),
                newCommand.getCommandUuid(),
                leaseId,
                fenceToken,
                clientSeq,
                idempotencyKey,
                payload,
                effectiveDeadline,
                reason,
                now);
        replaceEventRepository.save(event);

        return new ReplaceResultView(toReplacementView(event), toNewCommandView(newCommand, event));
    }

    private ReplaceResultView replayResult(ReplaceEvent event) {
        CommandRecord newCommand = commandRepository.findById(event.getNewCommandId())
                .orElseThrow(() -> new IllegalStateException(
                        "replacement new command missing for replace id: " + event.getReplaceId()));
        return new ReplaceResultView(toReplacementView(event), toNewCommandView(newCommand, event));
    }

    private static boolean sameReplaceRequest(ReplaceEvent event, String leaseId, long fenceToken,
                                              long clientSeq, String idempotencyKey,
                                              String payload, Instant effectiveDeadline,
                                              String reason) {
        return event.getLeaseId().equals(leaseId)
                && event.getFenceToken() == fenceToken
                && event.getClientSeq() == clientSeq
                && event.getIdempotencyKey().equals(idempotencyKey)
                && event.getPayload().equals(payload)
                && Objects.equals(event.getDeadlineAt(), effectiveDeadline)
                && Objects.equals(event.getReason(), reason);
    }

    private static ReplacementView toReplacementView(ReplaceEvent event) {
        return new ReplacementView(
                event.getReplaceId(),
                event.getOldCommandUuid(),
                event.getNewCommandUuid(),
                event.getFenceToken(),
                event.getClientSeq(),
                event.getIdempotencyKey(),
                event.getReason(),
                event.getReplacedAt());
    }

    private static CommandView toNewCommandView(CommandRecord newCommand, ReplaceEvent event) {
        return new CommandView(
                newCommand.getCommandUuid(),
                newCommand.getDeviceId(),
                newCommand.getLeaseId(),
                newCommand.getFenceToken(),
                newCommand.getClientSeq(),
                newCommand.getIdempotencyKey(),
                newCommand.getPayload(),
                newCommand.getAcceptOrder(),
                newCommand.getState(),
                newCommand.getAcceptedAt(),
                newCommand.getDeadlineAt(),
                newCommand.getDispatchedAt(),
                null,
                java.util.List.of(),
                null,
                toReplacementView(event));
    }
}
