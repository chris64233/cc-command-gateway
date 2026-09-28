package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.commandgateway.domain.CommandRecord;
import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.domain.Lease;
import com.chris64233.cc.commandgateway.domain.ReceiptEvent;
import com.chris64233.cc.commandgateway.domain.ReceiptKind;
import com.chris64233.cc.commandgateway.repo.CommandRepository;
import com.chris64233.cc.commandgateway.repo.LeaseRepository;
import com.chris64233.cc.commandgateway.repo.ReceiptEventRepository;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;

import jakarta.persistence.LockModeType;

@Service
public class ReceiptService {

    private final CommandRepository commandRepository;
    private final LeaseRepository leaseRepository;
    private final ReceiptEventRepository receiptEventRepository;
    private final Clock clock;

    public ReceiptService(CommandRepository commandRepository,
                          LeaseRepository leaseRepository,
                          ReceiptEventRepository receiptEventRepository,
                          Clock clock) {
        this.commandRepository = commandRepository;
        this.leaseRepository = leaseRepository;
        this.receiptEventRepository = receiptEventRepository;
        this.clock = clock;
    }

    @Transactional
    public ReceiptView report(String deviceId, String commandUuid, String leaseId, long fenceToken,
                              String eventId, ReceiptKind kind, String content) {
        Instant now = Instant.now(clock);

        CommandRecord command = commandRepository.findWithLockingByCommandUuid(commandUuid)
                .orElseThrow(() -> ApiException.notFound(
                        "unknown command: " + commandUuid));
        if (!command.getDeviceId().equals(deviceId)) {
            throw ApiException.notFound("unknown command: " + commandUuid);
        }

        Lease lease = leaseRepository.findByLeaseId(leaseId)
                .orElseThrow(() -> ApiException.conflict("invalid_lease", "unknown lease: " + leaseId));
        if (!lease.getDeviceId().equals(deviceId)) {
            throw ApiException.conflict("invalid_lease",
                    "lease does not belong to device: " + deviceId);
        }
        if (fenceToken != lease.getFenceToken() || fenceToken != command.getFenceToken()) {
            throw ApiException.conflict("fence_token_mismatch",
                    "receipt fence token does not match the accepted command token");
        }

        ReceiptEvent prior = receiptEventRepository.findByEventId(eventId).orElse(null);
        if (prior != null) {
            if (!prior.getCommandId().equals(command.getId())
                    || prior.getKind() != kind
                    || !Objects.equals(prior.getContent(), content)) {
                throw ApiException.conflict("receipt_event_conflict",
                        "receipt event id was already used with different content: " + eventId);
            }
            return toView(prior);
        }

        if (command.getState().isTerminal()) {
            if (command.getState().isAborted()) {
                // 已取消/已超时/已被替换指令的迟到回执：作为异常记录保留，但不得把指令改回成功。
                ReceiptEvent late = new ReceiptEvent(
                        eventId,
                        command.getId(),
                        commandUuid,
                        fenceToken,
                        kind,
                        content,
                        now,
                        true);
                receiptEventRepository.save(late);
                return toView(late);
            }
            throw ApiException.conflict("terminal_receipt",
                    "command already reached terminal state: " + command.getState());
        }
        if (kind == ReceiptKind.ACK && command.getState() == CommandState.ACKNOWLEDGED) {
            throw ApiException.conflict("receipt_state_conflict",
                    "command is already acknowledged; duplicate ack event id required");
        }

        ReceiptEvent receipt = new ReceiptEvent(
                eventId,
                command.getId(),
                commandUuid,
                fenceToken,
                kind,
                content,
                now,
                false);
        receiptEventRepository.save(receipt);

        command.setState(switch (kind) {
            case ACK -> CommandState.ACKNOWLEDGED;
            case SUCCEEDED -> CommandState.SUCCEEDED;
            case FAILED -> CommandState.FAILED;
        });

        return toView(receipt);
    }

    private static ReceiptView toView(ReceiptEvent receipt) {
        return new ReceiptView(
                receipt.getEventId(),
                receipt.getCommandUuid(),
                receipt.getFenceToken(),
                receipt.getKind(),
                receipt.getContent(),
                receipt.getReceivedAt(),
                receipt.isAnomalous());
    }
}
