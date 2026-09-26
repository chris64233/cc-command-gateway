package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.commandgateway.domain.CancelEvent;
import com.chris64233.cc.commandgateway.domain.CommandRecord;
import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.domain.Device;
import com.chris64233.cc.commandgateway.domain.Lease;
import com.chris64233.cc.commandgateway.domain.ReceiptEvent;
import com.chris64233.cc.commandgateway.repo.CancelEventRepository;
import com.chris64233.cc.commandgateway.repo.CommandRepository;
import com.chris64233.cc.commandgateway.repo.DeviceRepository;
import com.chris64233.cc.commandgateway.repo.LeaseRepository;
import com.chris64233.cc.commandgateway.repo.ReceiptEventRepository;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CancelView;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;

@Service
public class CommandService {

    private final DeviceRepository deviceRepository;
    private final LeaseRepository leaseRepository;
    private final CommandRepository commandRepository;
    private final ReceiptEventRepository receiptEventRepository;
    private final CancelEventRepository cancelEventRepository;
    private final Clock clock;

    public CommandService(DeviceRepository deviceRepository,
                          LeaseRepository leaseRepository,
                          CommandRepository commandRepository,
                          ReceiptEventRepository receiptEventRepository,
                          CancelEventRepository cancelEventRepository,
                          Clock clock) {
        this.deviceRepository = deviceRepository;
        this.leaseRepository = leaseRepository;
        this.commandRepository = commandRepository;
        this.receiptEventRepository = receiptEventRepository;
        this.cancelEventRepository = cancelEventRepository;
        this.clock = clock;
    }

    @Transactional
    public CommandView submit(String deviceId, String leaseId, long fenceToken,
                              long clientSeq, String idempotencyKey, String payload) {
        return submit(deviceId, leaseId, fenceToken, clientSeq, idempotencyKey, payload, null);
    }

    @Transactional
    public CommandView submit(String deviceId, String leaseId, long fenceToken,
                              long clientSeq, String idempotencyKey, String payload,
                              Instant deadlineAt) {
        Instant now = Instant.now(clock);

        Device device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));

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
        if (fenceToken != device.getFenceToken()) {
            throw ApiException.conflict("stale_fence_token",
                    "fence token is not the current token of the device");
        }
        if (lease.isExpiredAt(now)) {
            throw ApiException.conflict("lease_expired", "lease has expired");
        }
        CommandRecord existing = commandRepository
                .findByDeviceIdAndIdempotencyKey(deviceId, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            if (!sameRequest(existing, leaseId, fenceToken, clientSeq, payload, deadlineAt)) {
                throw ApiException.conflict("idempotency_conflict",
                        "idempotency key was already used with a different request: "
                                + idempotencyKey);
            }
            return toView(existing, null, List.of());
        }

        if (clientSeq <= lease.getLastAcceptedSeq()) {
            throw ApiException.conflict("stale_client_seq",
                    "clientSeq must be greater than the last accepted sequence: "
                            + lease.getLastAcceptedSeq());
        }

        long acceptOrder = device.getNextAcceptOrder();
        device.advanceAcceptOrder();
        lease.setLastAcceptedSeq(clientSeq);

        CommandRecord command = new CommandRecord(
                UUID.randomUUID().toString(),
                deviceId,
                leaseId,
                fenceToken,
                clientSeq,
                idempotencyKey,
                payload,
                acceptOrder,
                now,
                deadlineAt);
        commandRepository.save(command);

        return toView(command, null, List.of());
    }

    /**
     * 派发指令到设备：PENDING → DISPATCHED。重复派发同一指令幂等返回；
     * 已进入设备执行阶段或终态的指令不可派发。
     */
    @Transactional
    public CommandView dispatch(String deviceId, String commandUuid) {
        Instant now = Instant.now(clock);

        CommandRecord command = commandRepository.findWithLockingByCommandUuid(commandUuid)
                .orElseThrow(() -> ApiException.notFound("unknown command: " + commandUuid));
        if (!command.getDeviceId().equals(deviceId)) {
            throw ApiException.notFound("unknown command: " + commandUuid);
        }

        if (command.getState() == CommandState.PENDING) {
            command.setState(CommandState.DISPATCHED);
            command.setDispatchedAt(now);
        } else if (command.getState() != CommandState.DISPATCHED) {
            throw ApiException.conflict("command_not_dispatchable",
                    "command cannot be dispatched from state: " + command.getState());
        }
        return toView(command, null, List.of());
    }

    @Transactional(readOnly = true)
    public List<CommandView> timeline(String deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));

        List<CommandRecord> commands = commandRepository.findByDeviceIdOrderByAcceptOrderAsc(deviceId);
        if (commands.isEmpty()) {
            return List.of();
        }

        List<Long> commandIds = commands.stream().map(CommandRecord::getId).toList();
        Map<Long, List<ReceiptEvent>> receiptsByCommand = receiptEventRepository
                .findByCommandIdInOrderByReceivedAtAsc(commandIds)
                .stream()
                .collect(Collectors.groupingBy(ReceiptEvent::getCommandId));
        Map<Long, CancelEvent> cancelsByCommand = cancelEventRepository
                .findByCommandIdIn(commandIds)
                .stream()
                .collect(Collectors.toMap(CancelEvent::getCommandId, cancel -> cancel));

        List<CommandView> views = new ArrayList<>(commands.size());
        for (CommandRecord command : commands) {
            List<ReceiptView> receipts = receiptsByCommand
                    .getOrDefault(command.getId(), List.of())
                    .stream()
                    .map(CommandService::toReceiptView)
                    .toList();
            CancelEvent cancel = cancelsByCommand.get(command.getId());
            views.add(toView(command, cancel == null ? null : toCancelView(cancel), receipts));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public DeviceStatus status(String deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));

        LeaseView currentLease = null;
        if (device.getCurrentLeaseId() != null) {
            Lease lease = leaseRepository.findByLeaseId(device.getCurrentLeaseId()).orElse(null);
            Instant now = Instant.now(clock);
            if (lease != null && !lease.isExpiredAt(now)) {
                currentLease = new LeaseView(
                        lease.getLeaseId(),
                        lease.getDeviceId(),
                        lease.getClientId(),
                        lease.getFenceToken(),
                        lease.getLastAcceptedSeq(),
                        lease.getAcquiredAt(),
                        lease.getExpiresAt(),
                        true);
            }
        }
        return new DeviceStatus(deviceId, device.getFenceToken(), currentLease);
    }

    private static boolean sameRequest(CommandRecord existing, String leaseId, long fenceToken,
                                       long clientSeq, String payload, Instant deadlineAt) {
        return existing.getLeaseId().equals(leaseId)
                && existing.getFenceToken() == fenceToken
                && existing.getClientSeq() == clientSeq
                && existing.getPayload().equals(payload)
                && Objects.equals(existing.getDeadlineAt(), deadlineAt);
    }

    private static ReceiptView toReceiptView(ReceiptEvent receipt) {
        return new ReceiptView(
                receipt.getEventId(),
                receipt.getCommandUuid(),
                receipt.getFenceToken(),
                receipt.getKind(),
                receipt.getContent(),
                receipt.getReceivedAt(),
                receipt.isAnomalous());
    }

    private static CancelView toCancelView(CancelEvent cancel) {
        return new CancelView(
                cancel.getCancelId(),
                cancel.getCommandUuid(),
                cancel.getFenceToken(),
                cancel.getKind(),
                cancel.getReason(),
                cancel.getCancelledAt());
    }

    private static CommandView toView(CommandRecord command, CancelView cancel,
                                      List<ReceiptView> receipts) {
        return new CommandView(
                command.getCommandUuid(),
                command.getDeviceId(),
                command.getLeaseId(),
                command.getFenceToken(),
                command.getClientSeq(),
                command.getIdempotencyKey(),
                command.getPayload(),
                command.getAcceptOrder(),
                command.getState(),
                command.getAcceptedAt(),
                command.getDeadlineAt(),
                command.getDispatchedAt(),
                cancel,
                receipts);
    }

    public record DeviceStatus(String deviceId, long currentFenceToken, LeaseView currentLease) {
    }
}
