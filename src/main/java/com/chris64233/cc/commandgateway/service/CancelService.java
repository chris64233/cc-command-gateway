package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.commandgateway.domain.CancelEvent;
import com.chris64233.cc.commandgateway.domain.CancelKind;
import com.chris64233.cc.commandgateway.domain.CommandRecord;
import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.domain.Device;
import com.chris64233.cc.commandgateway.domain.Lease;
import com.chris64233.cc.commandgateway.repo.CancelEventRepository;
import com.chris64233.cc.commandgateway.repo.CommandRepository;
import com.chris64233.cc.commandgateway.repo.DeviceRepository;
import com.chris64233.cc.commandgateway.repo.LeaseRepository;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CancelView;

@Service
public class CancelService {

    /** 超时取消使用确定性的取消号，扫描重试天然幂等。 */
    public static final String TIMEOUT_CANCEL_ID_PREFIX = "timeout:";

    private final DeviceRepository deviceRepository;
    private final LeaseRepository leaseRepository;
    private final CommandRepository commandRepository;
    private final CancelEventRepository cancelEventRepository;
    private final Clock clock;

    public CancelService(DeviceRepository deviceRepository,
                         LeaseRepository leaseRepository,
                         CommandRepository commandRepository,
                         CancelEventRepository cancelEventRepository,
                         Clock clock) {
        this.deviceRepository = deviceRepository;
        this.leaseRepository = leaseRepository;
        this.commandRepository = commandRepository;
        this.cancelEventRepository = cancelEventRepository;
        this.clock = clock;
    }

    /**
     * 客户端主动取消。要求当前有效租约，且栅栏令牌不小于原指令令牌；
     * 只允许取消尚未进入设备执行阶段（未收到 ACK）的指令。
     */
    @Transactional
    public CancelView cancel(String deviceId, String commandUuid, String leaseId, long fenceToken,
                             String cancelId, String reason) {
        Instant now = Instant.now(clock);

        if (cancelId.startsWith(TIMEOUT_CANCEL_ID_PREFIX)) {
            throw ApiException.conflict("reserved_cancel_id",
                    "cancel id prefix is reserved for timeout scans: " + cancelId);
        }

        Device device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));

        CommandRecord command = commandRepository.findWithLockingByCommandUuid(commandUuid)
                .orElseThrow(() -> ApiException.notFound("unknown command: " + commandUuid));
        if (!command.getDeviceId().equals(deviceId)) {
            throw ApiException.notFound("unknown command: " + commandUuid);
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
        if (fenceToken != device.getFenceToken() || fenceToken < command.getFenceToken()) {
            throw ApiException.conflict("stale_fence_token",
                    "fence token is not the current token of the device"
                            + " or is older than the command token");
        }
        if (lease.isExpiredAt(now)) {
            throw ApiException.conflict("lease_expired", "lease has expired");
        }

        CancelEvent prior = cancelEventRepository.findByCancelId(cancelId).orElse(null);
        if (prior != null) {
            if (!prior.getCommandId().equals(command.getId())) {
                throw ApiException.conflict("cancel_event_conflict",
                        "cancel id was already used for a different command: " + cancelId);
            }
            return toView(prior);
        }

        return applyCancel(command, cancelId, CancelKind.CLIENT, reason, fenceToken, now);
    }

    /**
     * 超时取消。与主动取消共享同一终态竞争规则（指令行悲观锁 + 状态判定），
     * 取消号确定性生成，扫描可安全重试且不重复产生取消事件。
     *
     * @return 本次扫描实际取消时返回取消记录；指令已不在可取消状态（竞争失败）返回 null
     */
    @Transactional
    public CancelView cancelTimedOut(String commandUuid) {
        Instant now = Instant.now(clock);

        CommandRecord command = commandRepository.findWithLockingByCommandUuid(commandUuid)
                .orElse(null);
        if (command == null) {
            return null;
        }

        String cancelId = TIMEOUT_CANCEL_ID_PREFIX + commandUuid;
        CancelEvent prior = cancelEventRepository.findByCancelId(cancelId).orElse(null);
        if (prior != null) {
            return toView(prior);
        }
        if (!command.getState().isCancellable()) {
            return null;
        }

        return applyCancel(command, cancelId, CancelKind.TIMEOUT, "deadline exceeded",
                command.getFenceToken(), now);
    }

    private CancelView applyCancel(CommandRecord command, String cancelId, CancelKind kind,
                                   String reason, long fenceToken, Instant now) {
        if (!command.getState().isCancellable()) {
            throw ApiException.conflict("command_not_cancellable",
                    "command cannot be cancelled from state: " + command.getState());
        }
        command.setState(kind == CancelKind.TIMEOUT
                ? CommandState.TIMED_OUT
                : CommandState.CANCELLED);
        CancelEvent event = new CancelEvent(
                cancelId,
                command.getId(),
                command.getCommandUuid(),
                fenceToken,
                kind,
                reason,
                now);
        cancelEventRepository.save(event);
        return toView(event);
    }

    private static CancelView toView(CancelEvent event) {
        return new CancelView(
                event.getCancelId(),
                event.getCommandUuid(),
                event.getFenceToken(),
                event.getKind(),
                event.getReason(),
                event.getCancelledAt());
    }
}
