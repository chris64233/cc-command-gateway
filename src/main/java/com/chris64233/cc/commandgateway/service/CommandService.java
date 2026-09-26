package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.commandgateway.domain.CommandRecord;
import com.chris64233.cc.commandgateway.domain.Device;
import com.chris64233.cc.commandgateway.domain.Lease;
import com.chris64233.cc.commandgateway.domain.ReceiptEvent;
import com.chris64233.cc.commandgateway.repo.CommandRepository;
import com.chris64233.cc.commandgateway.repo.DeviceRepository;
import com.chris64233.cc.commandgateway.repo.LeaseRepository;
import com.chris64233.cc.commandgateway.repo.ReceiptEventRepository;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;

@Service
public class CommandService {

    private final DeviceRepository deviceRepository;
    private final LeaseRepository leaseRepository;
    private final CommandRepository commandRepository;
    private final ReceiptEventRepository receiptEventRepository;
    private final Clock clock;

    public CommandService(DeviceRepository deviceRepository,
                          LeaseRepository leaseRepository,
                          CommandRepository commandRepository,
                          ReceiptEventRepository receiptEventRepository,
                          Clock clock) {
        this.deviceRepository = deviceRepository;
        this.leaseRepository = leaseRepository;
        this.commandRepository = commandRepository;
        this.receiptEventRepository = receiptEventRepository;
        this.clock = clock;
    }

    @Transactional
    public CommandView submit(String deviceId, String leaseId, long fenceToken,
                              long clientSeq, String idempotencyKey, String payload) {
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
            if (!sameRequest(existing, leaseId, fenceToken, clientSeq, payload)) {
                throw ApiException.conflict("idempotency_conflict",
                        "idempotency key was already used with a different request: "
                                + idempotencyKey);
            }
            return toView(existing, List.of());
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
                now);
        commandRepository.save(command);

        return toView(command, List.of());
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

        List<CommandView> views = new ArrayList<>(commands.size());
        for (CommandRecord command : commands) {
            List<ReceiptView> receipts = receiptsByCommand
                    .getOrDefault(command.getId(), List.of())
                    .stream()
                    .map(CommandService::toReceiptView)
                    .toList();
            views.add(toView(command, receipts));
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
                                       long clientSeq, String payload) {
        return existing.getLeaseId().equals(leaseId)
                && existing.getFenceToken() == fenceToken
                && existing.getClientSeq() == clientSeq
                && existing.getPayload().equals(payload);
    }

    private static ReceiptView toReceiptView(ReceiptEvent receipt) {
        return new ReceiptView(
                receipt.getEventId(),
                receipt.getCommandUuid(),
                receipt.getFenceToken(),
                receipt.getKind(),
                receipt.getContent(),
                receipt.getReceivedAt());
    }

    private static CommandView toView(CommandRecord command, List<ReceiptView> receipts) {
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
                receipts);
    }

    public record DeviceStatus(String deviceId, long currentFenceToken, LeaseView currentLease) {
    }
}
