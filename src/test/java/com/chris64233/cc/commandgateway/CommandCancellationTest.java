package com.chris64233.cc.commandgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.chris64233.cc.commandgateway.domain.CommandEventKind;
import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.domain.ReceiptKind;
import com.chris64233.cc.commandgateway.service.CommandLifecycleService;
import com.chris64233.cc.commandgateway.service.CommandService;
import com.chris64233.cc.commandgateway.service.DeviceService;
import com.chris64233.cc.commandgateway.service.LeaseService;
import com.chris64233.cc.commandgateway.service.ReceiptService;
import com.chris64233.cc.commandgateway.support.MutableClock;
import com.chris64233.cc.commandgateway.support.TestClockConfig;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CommandEventView;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;

@SpringBootTest
@Import(TestClockConfig.class)
class CommandCancellationTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private LeaseService leaseService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private CommandLifecycleService lifecycleService;
    @Autowired
    private ReceiptService receiptService;
    @Autowired
    private MutableClock clock;

    private String device;
    private LeaseView lease;
    private CommandView command;
    private String run;

    @BeforeEach
    void setUp() {
        run = UUID.randomUUID().toString().substring(0, 8);
        device = "dev-cancel-" + UUID.randomUUID();
        deviceService.register(device);
        lease = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofHours(1)));
        command = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                1L, "cmd-1", "payload");
    }

    /** 回执事件号与取消号全局唯一，测试内加随机后缀避免相互污染。 */
    private String id(String base) {
        return base + "-" + run;
    }

    private CommandView cancel(String cancelId) {
        return lifecycleService.cancel(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), cancelId, "no longer needed");
    }

    private CommandView timelineCommand() {
        return commandService.timeline(device).get(0);
    }

    @Test
    void cancelPendingCommandRecordsCancelledStateAndEvent() {
        CommandView view = cancel(id("cancel-1"));

        assertThat(view.state()).isEqualTo(CommandState.CANCELLED);

        CommandView persisted = timelineCommand();
        assertThat(persisted.state()).isEqualTo(CommandState.CANCELLED);
        assertThat(persisted.events().stream().map(CommandEventView::kind))
                .containsExactly(CommandEventKind.ACCEPTED, CommandEventKind.CANCELLED);
        CommandEventView cancelEvent = persisted.events().get(1);
        assertThat(cancelEvent.eventId()).isEqualTo(id("cancel-1"));
        assertThat(cancelEvent.detail()).isEqualTo("no longer needed");
        assertThat(cancelEvent.fenceToken()).isEqualTo(lease.fenceToken());
    }

    @Test
    void cancelReplayWithSameCancelIdIsIdempotent() {
        CommandView first = cancel(id("cancel-1"));
        CommandView replay = cancel(id("cancel-1"));

        assertThat(replay.state()).isEqualTo(CommandState.CANCELLED);
        assertThat(replay.commandUuid()).isEqualTo(first.commandUuid());
        assertThat(timelineCommand().events().stream()
                .filter(event -> event.kind() == CommandEventKind.CANCELLED))
                .hasSize(1);
    }

    @Test
    void sameCancelIdForDifferentCommandConflicts() {
        CommandView other = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                2L, "cmd-2", "payload");
        cancel(id("cancel-shared"));

        assertThatThrownBy(() -> lifecycleService.cancel(device, other.commandUuid(),
                lease.leaseId(), lease.fenceToken(), id("cancel-shared"), "again"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("cancel_event_conflict");
    }

    @Test
    void differentCancelIdAfterCancellationConflicts() {
        cancel(id("cancel-1"));

        assertThatThrownBy(() -> cancel(id("cancel-2")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_already_cancelled");
        assertThat(timelineCommand().state()).isEqualTo(CommandState.CANCELLED);
    }

    @Test
    void cancelRequiresCurrentValidLease() {
        clock.advance(Duration.ofMinutes(61));

        // 旧租约已过期，不能取消
        assertThatThrownBy(() -> cancel(id("cancel-expired")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("lease_expired");

        // 新租约生效后，旧租约不再是当前租约
        LeaseView next = leaseService.acquire(device, "client-b",
                clock.instant().plus(Duration.ofHours(1)));
        assertThatThrownBy(() -> cancel(id("cancel-stale")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("stale_lease");

        // 新控制者以更大的栅栏令牌取消旧指令（令牌不小于原指令令牌）
        CommandView view = lifecycleService.cancel(device, command.commandUuid(),
                next.leaseId(), next.fenceToken(), id("cancel-by-new-owner"), "takeover");
        assertThat(view.state()).isEqualTo(CommandState.CANCELLED);
        assertThat(next.fenceToken()).isGreaterThan(command.fenceToken());
    }

    @Test
    void cancelWithMismatchedFenceTokenRejected() {
        assertThatThrownBy(() -> lifecycleService.cancel(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken() + 1, id("cancel-bad-token"), "reason"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("fence_token_mismatch");
        assertThat(timelineCommand().state()).isEqualTo(CommandState.PENDING);
    }

    @Test
    void dispatchMarksCommandDispatchedAndIsIdempotent() {
        CommandView dispatched = lifecycleService.dispatch(device, command.commandUuid());
        assertThat(dispatched.state()).isEqualTo(CommandState.DISPATCHED);

        CommandView again = lifecycleService.dispatch(device, command.commandUuid());
        assertThat(again.state()).isEqualTo(CommandState.DISPATCHED);

        assertThat(timelineCommand().events().stream().map(CommandEventView::kind))
                .containsExactly(CommandEventKind.ACCEPTED, CommandEventKind.DISPATCHED);
    }

    @Test
    void cancelDispatchedCommandSucceeds() {
        lifecycleService.dispatch(device, command.commandUuid());

        CommandView view = cancel(id("cancel-dispatched"));

        assertThat(view.state()).isEqualTo(CommandState.CANCELLED);
        assertThat(timelineCommand().events().stream().map(CommandEventView::kind))
                .containsExactly(CommandEventKind.ACCEPTED, CommandEventKind.DISPATCHED,
                        CommandEventKind.CANCELLED);
    }

    @Test
    void dispatchOfNonPendingCommandConflicts() {
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), id("ack-1"), ReceiptKind.ACK, "seen");

        assertThatThrownBy(() -> lifecycleService.dispatch(device, command.commandUuid()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("dispatch_state_conflict");
    }

    @Test
    void cancelAcknowledgedCommandRejectedAndDoesNotFakeSuccess() {
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), id("ack-1"), ReceiptKind.ACK, "seen");

        assertThatThrownBy(() -> cancel(id("cancel-too-late")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_already_started");

        CommandView persisted = timelineCommand();
        assertThat(persisted.state()).isEqualTo(CommandState.ACKNOWLEDGED);
        assertThat(persisted.events().stream().map(CommandEventView::kind))
                .containsExactly(CommandEventKind.ACCEPTED);
    }

    @Test
    void cancelSucceededCommandRejected() {
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), id("succ-1"), ReceiptKind.SUCCEEDED, "done");

        assertThatThrownBy(() -> cancel(id("cancel-after-success")))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_already_terminal");
        assertThat(timelineCommand().state()).isEqualTo(CommandState.SUCCEEDED);
    }

    @Test
    void lateSuccessReceiptAfterCancelKeptAsAnomalyWithoutRevivingCommand() {
        cancel(id("cancel-1"));

        ReceiptView late = receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), id("late-success"),
                ReceiptKind.SUCCEEDED, "done");

        assertThat(late.late()).isTrue();

        CommandView persisted = timelineCommand();
        assertThat(persisted.state()).isEqualTo(CommandState.CANCELLED);
        assertThat(persisted.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.eventId()).isEqualTo(id("late-success"));
            assertThat(receipt.late()).isTrue();
        });

        // 迟到回执的事件编号同样幂等
        ReceiptView replay = receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), id("late-success"),
                ReceiptKind.SUCCEEDED, "done");
        assertThat(replay).isEqualTo(late);
        assertThat(timelineCommand().receipts()).hasSize(1);
    }

    @Test
    void concurrentCancelAndDeviceStartResolveToExactlyOneOutcome() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> cancelError = new AtomicReference<>();
        AtomicReference<Throwable> ackError = new AtomicReference<>();

        Future<?> cancelFuture = pool.submit(() -> {
            try {
                start.await();
                cancel(id("cancel-race"));
            } catch (Throwable ex) {
                cancelError.set(ex);
            }
        });
        Future<?> ackFuture = pool.submit(() -> {
            try {
                start.await();
                receiptService.report(device, command.commandUuid(), lease.leaseId(),
                        lease.fenceToken(), id("ack-race"), ReceiptKind.ACK, "started");
            } catch (Throwable ex) {
                ackError.set(ex);
            }
        });

        start.countDown();
        cancelFuture.get(30, TimeUnit.SECONDS);
        ackFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        CommandView persisted = timelineCommand();
        if (persisted.state() == CommandState.CANCELLED) {
            // 取消胜出：设备开始回执只能作为迟到异常记录保留
            assertThat(cancelError.get()).isNull();
            assertThat(persisted.receipts())
                    .allSatisfy(receipt -> assertThat(receipt.late()).isTrue());
        } else {
            // 设备已开始：取消必须失败，指令不得伪装成取消成功
            assertThat(persisted.state()).isEqualTo(CommandState.ACKNOWLEDGED);
            assertThat(ackError.get()).isNull();
            assertThat(cancelError.get()).isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("command_already_started");
        }
    }

    @Test
    void timelineDistinguishesLifecycleStages() {
        lifecycleService.dispatch(device, command.commandUuid());
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), id("ack-1"), ReceiptKind.ACK, "started");
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), id("succ-1"), ReceiptKind.SUCCEEDED, "done");

        CommandView view = timelineCommand();
        assertThat(view.events().stream().map(CommandEventView::kind))
                .containsExactly(CommandEventKind.ACCEPTED, CommandEventKind.DISPATCHED);
        assertThat(view.receipts().stream().map(ReceiptView::kind))
                .containsExactly(ReceiptKind.ACK, ReceiptKind.SUCCEEDED);
        assertThat(view.receipts()).allSatisfy(receipt -> assertThat(receipt.late()).isFalse());
        assertThat(view.state()).isEqualTo(CommandState.SUCCEEDED);
    }

    @Test
    void cancelOnUnknownCommandOrDeviceRejected() {
        assertThatThrownBy(() -> lifecycleService.cancel(device, UUID.randomUUID().toString(),
                lease.leaseId(), lease.fenceToken(), id("cancel-missing"), "reason"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("not_found");

        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline).hasSize(1);
    }
}
