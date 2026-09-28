package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import com.chris64233.cc.commandgateway.web.dto.ReplaceView;

/**
 * 替换命令：用一条新命令完整取代尚未进入设备执行阶段（PENDING / DISPATCHED）的旧命令。
 *
 * <p>旧命令不删除，而是进入可追溯的 {@code REPLACED} 终态；新命令继承同一设备上下文
 * （deviceId）与业务上下文（当前租约、栅栏令牌、设备接受顺序），并通过 {@link ReplaceEvent}
 * 与旧命令建立关系。旧命令终态、新命令、替换关系与时间线在同一事务内提交。
 *
 * <p>替换与设备开始执行（ACK）/取消/超时在旧指令行悲观锁上竞争，只会形成一个结果：
 * 设备先 ACK 则替换被拒绝；替换先提交，则旧命令迟到回执只作异常记录。
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
     * 替换旧命令。
     *
     * @param replaceId 外部替换请求号，保证幂等；同号不同内容返回冲突
     */
    @Transactional
    public CommandView replace(String deviceId, String oldCommandUuid, String leaseId,
                               long fenceToken, long clientSeq, String replaceId,
                               String payload, Instant deadlineAt, String reason) {
        Instant now = Instant.now(clock);

        // 与取消相同的加锁顺序：先设备行，再旧指令行，避免与取消/超时之间出现死锁。
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

        // 幂等：同替换号先查既有替换关系，重放返回首次生成的新命令。
        ReplaceEvent prior = replaceEventRepository.findByReplaceId(replaceId).orElse(null);
        if (prior != null) {
            if (!prior.getOldCommandId().equals(oldCommand.getId())) {
                throw ApiException.conflict("replace_event_conflict",
                        "replace id was already used for a different command: " + replaceId);
            }
            CommandRecord replayed = commandRepository.findById(prior.getNewCommandId())
                    .orElseThrow(() -> ApiException.conflict("replace_event_conflict",
                            "replacement command is missing for replace id: " + replaceId));
            if (!sameNewRequest(replayed, leaseId, fenceToken, clientSeq, payload, deadlineAt)) {
                throw ApiException.conflict("idempotency_conflict",
                        "replace id was already used with a different request: " + replaceId);
            }
            return toView(replayed, prior);
        }

        if (!oldCommand.getState().isReplaceable()) {
            throw ApiException.conflict("command_not_replaceable",
                    "command cannot be replaced from state: " + oldCommand.getState());
        }

        // 新命令沿用替换号作为幂等键；若该键已被一条普通指令占用，拒绝而不是复用。
        commandRepository.findByDeviceIdAndIdempotencyKey(deviceId, replaceId)
                .ifPresent(existing -> {
                    throw ApiException.conflict("idempotency_conflict",
                            "replace id collides with an existing command idempotency key: "
                                    + replaceId);
                });

        if (clientSeq <= lease.getLastAcceptedSeq()) {
            throw ApiException.conflict("stale_client_seq",
                    "clientSeq must be greater than the last accepted sequence: "
                            + lease.getLastAcceptedSeq());
        }

        long acceptOrder = device.getNextAcceptOrder();
        device.advanceAcceptOrder();
        lease.setLastAcceptedSeq(clientSeq);

        CommandRecord newCommand = new CommandRecord(
                UUID.randomUUID().toString(),
                deviceId,
                leaseId,
                fenceToken,
                clientSeq,
                replaceId,
                payload,
                acceptOrder,
                now,
                deadlineAt);
        commandRepository.save(newCommand);

        // 旧命令进入可追溯终态，不删除。
        oldCommand.setState(CommandState.REPLACED);

        ReplaceEvent event = new ReplaceEvent(
                replaceId,
                deviceId,
                oldCommand.getId(),
                oldCommand.getCommandUuid(),
                newCommand.getId(),
                newCommand.getCommandUuid(),
                fenceToken,
                reason,
                now);
        replaceEventRepository.save(event);

        return toView(newCommand, event);
    }

    private static boolean sameNewRequest(CommandRecord newCommand, String leaseId, long fenceToken,
                                          long clientSeq, String payload, Instant deadlineAt) {
        return newCommand.getLeaseId().equals(leaseId)
                && newCommand.getFenceToken() == fenceToken
                && newCommand.getClientSeq() == clientSeq
                && newCommand.getPayload().equals(payload)
                && Objects.equals(newCommand.getDeadlineAt(), deadlineAt);
    }

    private static ReplaceView toReplaceView(ReplaceEvent event) {
        return new ReplaceView(
                event.getReplaceId(),
                event.getOldCommandUuid(),
                event.getNewCommandUuid(),
                event.getFenceToken(),
                event.getReason(),
                event.getReplacedAt());
    }

    private static CommandView toView(CommandRecord newCommand, ReplaceEvent event) {
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
                List.of(),
                toReplaceView(event));
    }
}
