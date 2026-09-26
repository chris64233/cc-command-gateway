package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.commandgateway.domain.CommandEvent;
import com.chris64233.cc.commandgateway.domain.CommandEventKind;
import com.chris64233.cc.commandgateway.domain.CommandRecord;
import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.domain.Device;
import com.chris64233.cc.commandgateway.domain.Lease;
import com.chris64233.cc.commandgateway.repo.CommandEventRepository;
import com.chris64233.cc.commandgateway.repo.CommandRepository;
import com.chris64233.cc.commandgateway.repo.DeviceRepository;
import com.chris64233.cc.commandgateway.repo.LeaseRepository;
import com.chris64233.cc.commandgateway.repo.ReceiptEventRepository;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CommandView;

/**
 * 指令生命周期：派发、客户端取消与超时扫描。
 *
 * <p>取消、设备回执与超时扫描都在指令行悲观写锁内完成终态竞争，
 * 因此对同一条指令只会形成「已取消 / 已超时 / 已开始执行」中的一种结果。
 */
@Service
public class CommandLifecycleService {

    private final DeviceRepository deviceRepository;
    private final LeaseRepository leaseRepository;
    private final CommandRepository commandRepository;
    private final ReceiptEventRepository receiptEventRepository;
    private final CommandEventRepository commandEventRepository;
    private final Clock clock;

    public CommandLifecycleService(DeviceRepository deviceRepository,
                                   LeaseRepository leaseRepository,
                                   CommandRepository commandRepository,
                                   ReceiptEventRepository receiptEventRepository,
                                   CommandEventRepository commandEventRepository,
                                   Clock clock) {
        this.deviceRepository = deviceRepository;
        this.leaseRepository = leaseRepository;
        this.commandRepository = commandRepository;
        this.receiptEventRepository = receiptEventRepository;
        this.commandEventRepository = commandEventRepository;
        this.clock = clock;
    }

    /** 将待派发指令标记为已派发；重复派发按幂等返回当前状态。 */
    @Transactional
    public CommandView dispatch(String deviceId, String commandUuid) {
        Instant now = Instant.now(clock);
        CommandRecord command = lockCommand(deviceId, commandUuid);

        if (command.getState() == CommandState.DISPATCHED) {
            return fullView(command);
        }
        if (command.getState() != CommandState.PENDING) {
            throw ApiException.conflict("dispatch_state_conflict",
                    "only pending commands can be dispatched, current state: " + command.getState());
        }

        command.setState(CommandState.DISPATCHED);
        commandEventRepository.save(new CommandEvent(
                "dispatch:" + command.getCommandUuid(),
                command.getId(),
                command.getCommandUuid(),
                command.getFenceToken(),
                CommandEventKind.DISPATCHED,
                null,
                now));
        return fullView(command);
    }

    /**
     * 客户端取消尚未进入设备执行阶段的指令。
     *
     * <p>取消号 {@code cancelId} 幂等：同一取消号重放返回首次结果；
     * 同一取消号绑定到不同指令返回冲突。
     */
    @Transactional
    public CommandView cancel(String deviceId, String commandUuid, String leaseId,
                              long fenceToken, String cancelId, String reason) {
        Instant now = Instant.now(clock);
        CommandRecord command = lockCommand(deviceId, commandUuid);

        CommandEvent priorCancel = commandEventRepository.findByEventId(cancelId).orElse(null);
        if (priorCancel != null) {
            if (!priorCancel.getCommandId().equals(command.getId())
                    || priorCancel.getKind() != CommandEventKind.CANCELLED) {
                throw ApiException.conflict("cancel_event_conflict",
                        "cancel id was already used for a different command: " + cancelId);
            }
            return fullView(command);
        }

        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));
        Lease lease = leaseRepository.findByLeaseId(leaseId)
                .orElseThrow(() -> ApiException.conflict("invalid_lease", "unknown lease: " + leaseId));
        if (!lease.getDeviceId().equals(deviceId)) {
            throw ApiException.conflict("invalid_lease",
                    "lease does not belong to device: " + deviceId);
        }
        if (!lease.getLeaseId().equals(device.getCurrentLeaseId())) {
            throw ApiException.conflict("stale_lease",
                    "lease is not the current lease of the device");
        }
        if (lease.isExpiredAt(now)) {
            throw ApiException.conflict("lease_expired", "lease has expired");
        }
        if (fenceToken != lease.getFenceToken()) {
            throw ApiException.conflict("fence_token_mismatch",
                    "fence token does not match lease token");
        }
        if (fenceToken < command.getFenceToken()) {
            throw ApiException.conflict("fence_token_too_low",
                    "fence token is lower than the command token: " + command.getFenceToken());
        }

        switch (command.getState()) {
            case PENDING, DISPATCHED -> {
                command.setState(CommandState.CANCELLED);
                commandEventRepository.save(new CommandEvent(
                        cancelId,
                        command.getId(),
                        command.getCommandUuid(),
                        fenceToken,
                        CommandEventKind.CANCELLED,
                        reason,
                        now));
                return fullView(command);
            }
            case ACKNOWLEDGED -> throw ApiException.conflict("command_already_started",
                    "command has already started executing on the device");
            case CANCELLED -> throw ApiException.conflict("command_already_cancelled",
                    "command was already cancelled with a different cancel id");
            default -> throw ApiException.conflict("command_already_terminal",
                    "command already reached terminal state: " + command.getState());
        }
    }

    /**
     * 超时扫描：把截止时间在 {@code now} 之前、仍未进入设备执行阶段的指令置为超时。
     *
     * <p>与主动取消共享同一终态竞争规则（指令行悲观锁）；超时事件使用确定性事件编号
     * {@code timeout:{commandUuid}}，扫描可安全重试，不会重复产生取消事件。
     */
    @Transactional
    public List<CommandView> scanTimeouts(String deviceId) {
        Instant now = Instant.now(clock);
        deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));

        List<CommandRecord> candidates = commandRepository
                .findByDeviceIdAndStateInAndDeadlineAtLessThanEqual(
                        deviceId,
                        List.of(CommandState.PENDING, CommandState.DISPATCHED),
                        now);

        List<CommandView> timedOut = new ArrayList<>();
        for (CommandRecord candidate : candidates) {
            CommandRecord command = lockCommand(deviceId, candidate.getCommandUuid());
            if (!command.getState().isCancellable()
                    || command.getDeadlineAt() == null
                    || command.getDeadlineAt().isAfter(now)) {
                continue;
            }
            command.setState(CommandState.TIMED_OUT);
            commandEventRepository.save(new CommandEvent(
                    "timeout:" + command.getCommandUuid(),
                    command.getId(),
                    command.getCommandUuid(),
                    command.getFenceToken(),
                    CommandEventKind.TIMED_OUT,
                    "deadline " + command.getDeadlineAt() + " exceeded",
                    now));
            timedOut.add(fullView(command));
        }
        return timedOut;
    }

    private CommandRecord lockCommand(String deviceId, String commandUuid) {
        CommandRecord command = commandRepository.findWithLockingByCommandUuid(commandUuid)
                .orElseThrow(() -> ApiException.notFound("unknown command: " + commandUuid));
        if (!command.getDeviceId().equals(deviceId)) {
            throw ApiException.notFound("unknown command: " + commandUuid);
        }
        return command;
    }

    private CommandView fullView(CommandRecord command) {
        List<Long> commandIds = List.of(command.getId());
        var events = commandEventRepository
                .findByCommandIdInOrderByOccurredAtAscIdAsc(commandIds)
                .stream()
                .map(CommandService::toEventView)
                .toList();
        var receipts = receiptEventRepository
                .findByCommandIdInOrderByReceivedAtAsc(commandIds)
                .stream()
                .map(CommandService::toReceiptView)
                .toList();
        return CommandService.toView(command, events, receipts);
    }
}
