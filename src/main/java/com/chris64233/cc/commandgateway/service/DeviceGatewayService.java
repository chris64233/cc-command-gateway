package com.chris64233.cc.commandgateway.service;

import com.chris64233.cc.commandgateway.domain.CommandReceipt;
import com.chris64233.cc.commandgateway.domain.CommandReceiptRepository;
import com.chris64233.cc.commandgateway.domain.CommandRecord;
import com.chris64233.cc.commandgateway.domain.CommandRecordRepository;
import com.chris64233.cc.commandgateway.domain.CommandStatus;
import com.chris64233.cc.commandgateway.domain.Device;
import com.chris64233.cc.commandgateway.domain.DeviceRepository;
import com.chris64233.cc.commandgateway.domain.Lease;
import com.chris64233.cc.commandgateway.domain.LeaseRepository;
import com.chris64233.cc.commandgateway.web.dto.AcquireLeaseRequest;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.DeviceStateView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptRequest;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;
import com.chris64233.cc.commandgateway.web.dto.SubmitCommandRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DeviceGatewayService {

    private static final Set<CommandStatus> TERMINAL_STATUSES =
            EnumSet.of(CommandStatus.SUCCEEDED, CommandStatus.FAILED);

    private final DeviceRepository deviceRepository;
    private final LeaseRepository leaseRepository;
    private final CommandRecordRepository commandRepository;
    private final CommandReceiptRepository receiptRepository;

    public DeviceGatewayService(DeviceRepository deviceRepository,
                                LeaseRepository leaseRepository,
                                CommandRecordRepository commandRepository,
                                CommandReceiptRepository receiptRepository) {
        this.deviceRepository = deviceRepository;
        this.leaseRepository = leaseRepository;
        this.commandRepository = commandRepository;
        this.receiptRepository = receiptRepository;
    }

    @Transactional
    public DeviceStateView registerDevice(String deviceNumber) {
        deviceRepository.findByDeviceNumber(deviceNumber).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_EXISTS",
                    "device already registered: " + deviceNumber);
        });
        Device device = deviceRepository.save(new Device(deviceNumber));
        return new DeviceStateView(device.getDeviceNumber(), device.getFencingToken(), null);
    }

    @Transactional(readOnly = true)
    public DeviceStateView getDeviceState(String deviceNumber) {
        Device device = findDevice(deviceNumber);
        LeaseView leaseView = null;
        if (device.getCurrentLeaseId() != null) {
            Lease lease = leaseRepository.findById(device.getCurrentLeaseId())
                    .orElseThrow(() -> new IllegalStateException("current lease row missing"));
            leaseView = LeaseView.from(lease, Instant.now());
        }
        return new DeviceStateView(device.getDeviceNumber(), device.getFencingToken(), leaseView);
    }

    @Transactional
    public LeaseView acquireLease(String deviceNumber, AcquireLeaseRequest request) {
        Device device = lockDevice(deviceNumber);
        Instant now = Instant.now();
        if (device.getCurrentLeaseId() != null) {
            Lease current = leaseRepository.findById(device.getCurrentLeaseId())
                    .orElseThrow(() -> new IllegalStateException("current lease row missing"));
            if (current.isActiveAt(now)) {
                throw new ApiException(HttpStatus.CONFLICT, "LEASE_ACTIVE",
                        "device " + deviceNumber + " already has an active lease held by "
                                + current.getClientId());
            }
        }
        long token = device.getFencingToken() + 1;
        device.setFencingToken(token);
        Lease lease = leaseRepository.save(new Lease(device.getId(), request.clientId(), token,
                now, now.plusMillis(request.ttlMillis())));
        device.setCurrentLeaseId(lease.getId());
        return LeaseView.from(lease, now);
    }

    @Transactional
    public CommandView submitCommand(String deviceNumber, SubmitCommandRequest request) {
        Device device = lockDevice(deviceNumber);
        Lease lease = leaseRepository.findById(request.leaseId())
                .filter(candidate -> candidate.getDeviceId().equals(device.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LEASE_NOT_FOUND",
                        "lease " + request.leaseId() + " does not exist on device " + deviceNumber));
        if (request.fencingToken() != device.getFencingToken()
                || lease.getFencingToken() != device.getFencingToken()) {
            throw new ApiException(HttpStatus.CONFLICT, "FENCING_TOKEN_MISMATCH",
                    "fencing token " + request.fencingToken() + " is not the current token "
                            + device.getFencingToken());
        }
        var existing = commandRepository.findByLeaseIdAndIdempotencyKey(
                lease.getId(), request.idempotencyKey());
        if (existing.isPresent()) {
            CommandRecord replay = existing.get();
            if (!replay.getPayload().equals(request.payload())) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "idempotency key " + request.idempotencyKey()
                                + " was already used with a different payload");
            }
            return CommandView.from(replay, List.of());
        }
        if (!lease.isActiveAt(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "LEASE_EXPIRED",
                    "lease " + lease.getId() + " expired at " + lease.getExpiresAt());
        }
        if (request.sequence() <= lease.getLastAcceptedSequence()) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_SEQUENCE",
                    "sequence " + request.sequence() + " is not greater than last accepted "
                            + lease.getLastAcceptedSequence());
        }
        CommandRecord command = commandRepository.save(new CommandRecord(device.getId(),
                lease.getId(), lease.getFencingToken(), request.sequence(),
                request.idempotencyKey(), request.payload(), Instant.now()));
        lease.setLastAcceptedSequence(request.sequence());
        return CommandView.from(command, List.of());
    }

    @Transactional
    public ReceiptView recordReceipt(String deviceNumber, long commandId, ReceiptRequest request) {
        Device device = findDevice(deviceNumber);
        CommandRecord command = commandRepository.findByIdForUpdate(commandId)
                .filter(candidate -> candidate.getDeviceId().equals(device.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COMMAND_NOT_FOUND",
                        "command " + commandId + " does not exist on device " + deviceNumber));
        if (request.fencingToken() != command.getFencingToken()) {
            throw new ApiException(HttpStatus.CONFLICT, "FENCING_TOKEN_MISMATCH",
                    "receipt token " + request.fencingToken() + " does not match command token "
                            + command.getFencingToken());
        }
        var existing = receiptRepository.findByCommandIdAndEventId(command.getId(), request.eventId());
        if (existing.isPresent()) {
            CommandReceipt replay = existing.get();
            if (replay.getStatus() != request.status()
                    || !Objects.equals(replay.getDetail(), request.detail())) {
                throw new ApiException(HttpStatus.CONFLICT, "RECEIPT_CONFLICT",
                        "receipt event " + request.eventId()
                                + " was already recorded with different content");
            }
            return ReceiptView.from(replay);
        }
        if (request.status().isTerminal()
                && receiptRepository.existsByCommandIdAndStatusIn(command.getId(), TERMINAL_STATUSES)) {
            throw new ApiException(HttpStatus.CONFLICT, "TERMINAL_STATE_LOCKED",
                    "command " + commandId + " already has a terminal receipt");
        }
        CommandReceipt receipt = receiptRepository.save(new CommandReceipt(command.getId(),
                request.eventId(), command.getFencingToken(), request.status(),
                request.detail(), Instant.now()));
        command.setStatus(request.status());
        return ReceiptView.from(receipt);
    }

    @Transactional(readOnly = true)
    public List<CommandView> getTimeline(String deviceNumber) {
        Device device = findDevice(deviceNumber);
        List<CommandRecord> commands = commandRepository.findByDeviceIdOrderBySequenceAsc(device.getId());
        if (commands.isEmpty()) {
            return List.of();
        }
        List<Long> commandIds = commands.stream().map(CommandRecord::getId).toList();
        Map<Long, List<CommandReceipt>> receiptsByCommand = receiptRepository
                .findByCommandIdInOrderByReceivedAtAsc(commandIds)
                .stream()
                .collect(Collectors.groupingBy(CommandReceipt::getCommandId));
        return commands.stream()
                .map(command -> CommandView.from(command,
                        receiptsByCommand.getOrDefault(command.getId(), List.of())))
                .toList();
    }

    private Device findDevice(String deviceNumber) {
        return deviceRepository.findByDeviceNumber(deviceNumber)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND",
                        "device not registered: " + deviceNumber));
    }

    private Device lockDevice(String deviceNumber) {
        return deviceRepository.findByDeviceNumberForUpdate(deviceNumber)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND",
                        "device not registered: " + deviceNumber));
    }
}
