package com.chris64233.cc.commandgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.chris64233.cc.commandgateway.domain.CancelKind;
import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.domain.ReceiptKind;
import com.chris64233.cc.commandgateway.service.CancelService;
import com.chris64233.cc.commandgateway.service.CommandService;
import com.chris64233.cc.commandgateway.service.DeviceService;
import com.chris64233.cc.commandgateway.service.LeaseService;
import com.chris64233.cc.commandgateway.service.ReceiptService;
import com.chris64233.cc.commandgateway.support.MutableClock;
import com.chris64233.cc.commandgateway.support.TestClockConfig;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CancelView;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;

@SpringBootTest
@Import(TestClockConfig.class)
class CancellationServiceTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private LeaseService leaseService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private ReceiptService receiptService;
    @Autowired
    private CancelService cancelService;
    @Autowired
    private MutableClock clock;

    private String device;
    private LeaseView lease;

    @BeforeEach
    void setUp() {
        device = "dev-cancel-" + UUID.randomUUID();
        deviceService.register(device);
        lease = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofHours(1)));
    }

    private CommandView submit(long seq, String key) {
        return commandService.submit(device, lease.leaseId(), lease.fenceToken(), seq, key, "payload");
    }

    private CancelView cancel(CommandView command, String cancelId) {
        return cancelService.cancel(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), cancelId, "user request");
    }

    @Test
    void cancelPendingCommandSucceeds() {
        CommandView command = submit(1L, "k1");

        CancelView cancel = cancel(command, "cancel-1");

        assertThat(cancel.kind()).isEqualTo(CancelKind.CLIENT);
        assertThat(cancel.cancelId()).isEqualTo("cancel-1");
        assertThat(cancel.cancelledAt()).isEqualTo(clock.instant());

        CommandView view = commandService.timeline(device).get(0);
        assertThat(view.state()).isEqualTo(CommandState.CANCELLED);
        assertThat(view.cancel().cancelId()).isEqualTo("cancel-1");
        assertThat(view.cancel().kind()).isEqualTo(CancelKind.CLIENT);
    }

    @Test
    void cancelDispatchedCommandSucceeds() {
        CommandView command = submit(1L, "k1");
        commandService.dispatch(device, command.commandUuid());

        cancel(command, "cancel-dispatched");

        CommandView view = commandService.timeline(device).get(0);
        assertThat(view.state()).isEqualTo(CommandState.CANCELLED);
        assertThat(view.dispatchedAt()).isNotNull();
    }

    @Test
    void cancelAcknowledgedCommandRejected() {
        CommandView command = submit(1L, "k1");
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "ack-cancel-loses", ReceiptKind.ACK, "started");

        assertThatThrownBy(() -> cancel(command, "cancel-too-late"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_cancellable");

        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.ACKNOWLEDGED);
    }

    @Test
    void executedCommandCannotBeCancelledAndKeepsTerminalState() {
        CommandView command = submit(1L, "k1");
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "done-cancel-test", ReceiptKind.SUCCEEDED, "ok");

        assertThatThrownBy(() -> cancel(command, "cancel-after-success"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_cancellable");

        CommandView view = commandService.timeline(device).get(0);
        assertThat(view.state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(view.cancel()).isNull();
    }

    @Test
    void cancelReplayWithSameCancelIdReturnsFirstRecord() {
        CommandView command = submit(1L, "k1");

        CancelView first = cancel(command, "cancel-replay");
        CancelView replay = cancel(command, "cancel-replay");

        assertThat(replay).isEqualTo(first);
        assertThat(commandService.timeline(device).get(0).cancel().cancelId())
                .isEqualTo("cancel-replay");
    }

    @Test
    void cancelIdReuseForDifferentCommandConflicts() {
        CommandView first = submit(1L, "k1");
        CommandView second = submit(2L, "k2");
        cancel(first, "cancel-shared");

        assertThatThrownBy(() -> cancel(second, "cancel-shared"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("cancel_event_conflict");

        assertThat(commandService.timeline(device).get(1).state())
                .isEqualTo(CommandState.PENDING);
    }

    @Test
    void cancelWithWrongFenceTokenRejected() {
        CommandView command = submit(1L, "k1");

        assertThatThrownBy(() -> cancelService.cancel(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken() + 1, "cancel-bad-token", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("fence_token_mismatch");
    }

    @Test
    void cancelWithExpiredLeaseRejected() {
        CommandView command = submit(1L, "k1");
        clock.advance(Duration.ofMinutes(61));

        assertThatThrownBy(() -> cancel(command, "cancel-expired"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("lease_expired");
    }

    @Test
    void cancelWithStaleFenceTokenRejected() {
        CommandView command = submit(1L, "k1");
        clock.advance(Duration.ofMinutes(61));
        leaseService.acquire(device, "client-b", clock.instant().plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> cancel(command, "cancel-stale"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("stale_fence_token");
    }

    @Test
    void newLeaseCanCancelCommandAcceptedUnderPreviousLease() {
        CommandView command = submit(1L, "k1");
        clock.advance(Duration.ofMinutes(61));
        LeaseView next = leaseService.acquire(device, "client-b",
                clock.instant().plus(Duration.ofHours(1)));

        CancelView cancel = cancelService.cancel(device, command.commandUuid(),
                next.leaseId(), next.fenceToken(), "cancel-takeover", "new owner cleanup");

        assertThat(cancel.kind()).isEqualTo(CancelKind.CLIENT);
        assertThat(cancel.fenceToken()).isEqualTo(next.fenceToken());
        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.CANCELLED);
    }

    @Test
    void cancelUnknownCommandReturnsNotFound() {
        assertThatThrownBy(() -> cancelService.cancel(device,
                "00000000-0000-0000-0000-000000000000",
                lease.leaseId(), lease.fenceToken(), "cancel-unknown", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("not_found");
    }

    @Test
    void timeoutCancelIdPrefixIsReservedForScans() {
        CommandView command = submit(1L, "k1");

        assertThatThrownBy(() -> cancel(command,
                CancelService.TIMEOUT_CANCEL_ID_PREFIX + command.commandUuid()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("reserved_cancel_id");

        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.PENDING);
    }

    @Test
    void lateSuccessReceiptAfterCancelIsKeptAsAnomalyWithoutChangingState() {
        CommandView command = submit(1L, "k1");
        cancel(command, "cancel-first");

        ReceiptView late = receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "late-success-cancel-test", ReceiptKind.SUCCEEDED, "ok");

        assertThat(late.anomalous()).isTrue();

        CommandView view = commandService.timeline(device).get(0);
        assertThat(view.state()).isEqualTo(CommandState.CANCELLED);
        assertThat(view.cancel().kind()).isEqualTo(CancelKind.CLIENT);
        assertThat(view.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.eventId()).isEqualTo("late-success-cancel-test");
            assertThat(receipt.anomalous()).isTrue();
        });
    }

    @Test
    void lateAnomalousReceiptReplayReturnsSameRecord() {
        CommandView command = submit(1L, "k1");
        cancel(command, "cancel-then-replay");

        ReceiptView first = receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "late-ack-cancel-test", ReceiptKind.ACK, "seen");
        ReceiptView replay = receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "late-ack-cancel-test", ReceiptKind.ACK, "seen");

        assertThat(first.anomalous()).isTrue();
        assertThat(replay).isEqualTo(first);
        assertThat(commandService.timeline(device).get(0).receipts()).hasSize(1);
    }
}
