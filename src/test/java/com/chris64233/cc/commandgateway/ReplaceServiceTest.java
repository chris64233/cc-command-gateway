package com.chris64233.cc.commandgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.domain.ReceiptKind;
import com.chris64233.cc.commandgateway.service.CancelService;
import com.chris64233.cc.commandgateway.service.CommandService;
import com.chris64233.cc.commandgateway.service.DeviceService;
import com.chris64233.cc.commandgateway.service.LeaseService;
import com.chris64233.cc.commandgateway.service.ReceiptService;
import com.chris64233.cc.commandgateway.service.ReplaceService;
import com.chris64233.cc.commandgateway.service.TimeoutService;
import com.chris64233.cc.commandgateway.support.MutableClock;
import com.chris64233.cc.commandgateway.support.TestClockConfig;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;

@SpringBootTest
@Import(TestClockConfig.class)
class ReplaceServiceTest {

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
    private TimeoutService timeoutService;
    @Autowired
    private ReplaceService replaceService;
    @Autowired
    private MutableClock clock;

    private String device;
    private LeaseView lease;

    @BeforeEach
    void setUp() {
        device = "dev-replace-" + UUID.randomUUID();
        deviceService.register(device);
        lease = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofHours(1)));
    }

    private CommandView submit(long seq, String key) {
        return commandService.submit(device, lease.leaseId(), lease.fenceToken(), seq, key, "old");
    }

    private CommandView replace(CommandView oldCommand, long seq, String replaceId, String payload) {
        return replaceService.replace(device, oldCommand.commandUuid(), lease.leaseId(),
                lease.fenceToken(), seq, replaceId, payload, null, "corrected command");
    }

    @Test
    void replacingPendingCommandMarksOldReplacedAndNewInheritsContext() {
        CommandView oldCommand = submit(1L, "k-old");

        CommandView newCommand = replace(oldCommand, 2L, "rep-inherit", "new");

        // 新命令继承设备与业务上下文，拥有自己的新身份与接受顺序
        assertThat(newCommand.commandUuid()).isNotEqualTo(oldCommand.commandUuid());
        assertThat(newCommand.deviceId()).isEqualTo(device);
        assertThat(newCommand.leaseId()).isEqualTo(lease.leaseId());
        assertThat(newCommand.fenceToken()).isEqualTo(lease.fenceToken());
        assertThat(newCommand.clientSeq()).isEqualTo(2L);
        assertThat(newCommand.idempotencyKey()).isEqualTo("rep-inherit");
        assertThat(newCommand.payload()).isEqualTo("new");
        assertThat(newCommand.state()).isEqualTo(CommandState.PENDING);
        assertThat(newCommand.acceptOrder()).isEqualTo(2L);

        List<CommandView> timeline = commandService.timeline(device);
        CommandView oldView = timeline.get(0);
        CommandView newView = timeline.get(1);

        // 旧命令保留可追溯的 REPLACED 终态，不删除
        assertThat(oldView.state()).isEqualTo(CommandState.REPLACED);
        assertThat(oldView.replacement()).isNotNull();
        assertThat(oldView.replacement().oldCommandUuid()).isEqualTo(oldCommand.commandUuid());
        assertThat(oldView.replacement().replacementCommandUuid())
                .isEqualTo(newCommand.commandUuid());
        assertThat(oldView.replacement().replaceId()).isEqualTo("rep-inherit");
        assertThat(oldView.replacement().replacedAt()).isEqualTo(clock.instant());

        // 新命令挂同一替换关系，关系与时间线在同一事务提交后可见
        assertThat(newView.state()).isEqualTo(CommandState.PENDING);
        assertThat(newView.replacement()).isNotNull();
        assertThat(newView.replacement().oldCommandUuid()).isEqualTo(oldCommand.commandUuid());
        assertThat(newView.replacement().replacementCommandUuid())
                .isEqualTo(newCommand.commandUuid());

        // 旧命令历史字段保留，可追溯
        assertThat(oldView.acceptOrder()).isEqualTo(1L);
        assertThat(oldView.payload()).isEqualTo("old");
        assertThat(oldView.acceptedAt()).isNotNull();
    }

    @Test
    void replacingDispatchedCommandSucceedsAndKeepsDispatchedTimeline() {
        CommandView oldCommand = submit(1L, "k-old");
        commandService.dispatch(device, oldCommand.commandUuid());

        CommandView newCommand = replace(oldCommand, 2L, "rep-dispatched", "new");

        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline.get(0).state()).isEqualTo(CommandState.REPLACED);
        assertThat(timeline.get(0).dispatchedAt()).isNotNull();
        assertThat(timeline.get(1).commandUuid()).isEqualTo(newCommand.commandUuid());
        assertThat(timeline.get(1).state()).isEqualTo(CommandState.PENDING);
        assertThat(timeline.get(1).dispatchedAt()).isNull();
    }

    @Test
    void acknowledgedCommandCannotBeReplaced() {
        CommandView oldCommand = submit(1L, "k-old");
        receiptService.report(device, oldCommand.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "ack-rep-before", ReceiptKind.ACK, "started");

        assertThatThrownBy(() -> replace(oldCommand, 2L, "rep-after-ack", "new"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");

        assertThat(commandService.timeline(device)).singleElement()
                .extracting(CommandView::state).isEqualTo(CommandState.ACKNOWLEDGED);
    }

    @Test
    void succeededCommandCannotBeReplaced() {
        CommandView oldCommand = submit(1L, "k-old");
        receiptService.report(device, oldCommand.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "done-rep-before", ReceiptKind.SUCCEEDED, "ok");

        assertThatThrownBy(() -> replace(oldCommand, 2L, "rep-after-success", "new"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");

        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.SUCCEEDED);
    }

    @Test
    void alreadyReplacedCommandCannotBeReplacedAgain() {
        CommandView oldCommand = submit(1L, "k-old");
        CommandView first = replace(oldCommand, 2L, "rep-again-1", "new-1");

        assertThatThrownBy(() -> replace(oldCommand, 3L, "rep-again-2", "new-2"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");

        // 第一次替换产生的新命令不受影响
        assertThat(commandService.timeline(device).get(1).commandUuid())
                .isEqualTo(first.commandUuid());
    }

    @Test
    void cancelledCommandCannotBeReplaced() {
        CommandView oldCommand = submit(1L, "k-old");
        cancelService.cancel(device, oldCommand.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "crep-cancelled", null);

        assertThatThrownBy(() -> replace(oldCommand, 2L, "rep-after-cancel", "new"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");
    }

    @Test
    void timedOutCommandCannotBeReplaced() {
        CommandView oldCommand = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                1L, "k-old", "old", clock.instant());
        timeoutService.scanDevice(device);

        assertThatThrownBy(() -> replace(oldCommand, 2L, "rep-after-timeout", "new"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");
    }

    @Test
    void replaceReplayWithSameIdReturnsSameNewCommand() {
        CommandView oldCommand = submit(1L, "k-old");

        CommandView first = replace(oldCommand, 2L, "rep-replay", "new");
        CommandView replay = replace(oldCommand, 2L, "rep-replay", "new");

        assertThat(replay).isEqualTo(first);
        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(1).commandUuid()).isEqualTo(first.commandUuid());
        // 重放不重复推进接受顺序与租约序号
        assertThat(leaseService.getCurrent(device).lastAcceptedSeq()).isEqualTo(2L);
    }

    @Test
    void sameReplaceIdWithDifferentPayloadConflicts() {
        CommandView oldCommand = submit(1L, "k-old");
        replace(oldCommand, 2L, "rep-diff-content", "payload-a");

        assertThatThrownBy(() -> replace(oldCommand, 2L, "rep-diff-content", "payload-b"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("idempotency_conflict");

        // 旧命令仍为第一次替换的结果，不产生第二条新命令
        assertThat(commandService.timeline(device)).hasSize(2);
        assertThat(commandService.timeline(device).get(1).payload()).isEqualTo("payload-a");
    }

    @Test
    void sameReplaceIdWithDifferentOldCommandConflicts() {
        CommandView first = submit(1L, "k-1");
        CommandView second = submit(2L, "k-2");
        replace(first, 3L, "rep-diff-old", "new");

        assertThatThrownBy(() -> replace(second, 4L, "rep-diff-old", "new-2"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("replace_event_conflict");

        // 第二条旧命令保持未替换
        CommandView secondView = commandService.timeline(device).get(1);
        assertThat(secondView.commandUuid()).isEqualTo(second.commandUuid());
        assertThat(secondView.state()).isEqualTo(CommandState.PENDING);
    }

    @Test
    void replaceAdvancesAcceptOrderAndLastAcceptedSeq() {
        CommandView oldCommand = submit(1L, "k-old");
        CommandView newCommand = replace(oldCommand, 5L, "rep-seq", "new");

        assertThat(newCommand.acceptOrder()).isEqualTo(2L);
        assertThat(leaseService.getCurrent(device).lastAcceptedSeq()).isEqualTo(5L);
    }

    @Test
    void replaceWithStaleClientSeqRejected() {
        CommandView oldCommand = submit(10L, "k-old");

        assertThatThrownBy(() -> replace(oldCommand, 10L, "rep-stale-seq", "new"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("stale_client_seq");

        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.PENDING);
    }

    @Test
    void replaceWithExpiredLeaseRejected() {
        CommandView oldCommand = submit(1L, "k-old");
        clock.advance(Duration.ofMinutes(61));

        assertThatThrownBy(() -> replace(oldCommand, 2L, "rep-expired", "new"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("lease_expired");
    }

    @Test
    void replaceWithStaleFenceTokenRejected() {
        CommandView oldCommand = submit(1L, "k-old");
        clock.advance(Duration.ofMinutes(61));
        leaseService.acquire(device, "client-b", clock.instant().plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> replace(oldCommand, 2L, "rep-stale-token", "new"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("stale_fence_token");
    }

    @Test
    void newLeaseCanReplaceCommandAcceptedUnderPreviousLease() {
        CommandView oldCommand = submit(1L, "k-old");
        clock.advance(Duration.ofMinutes(61));
        LeaseView next = leaseService.acquire(device, "client-b",
                clock.instant().plus(Duration.ofHours(1)));

        CommandView newCommand = replaceService.replace(device, oldCommand.commandUuid(),
                next.leaseId(), next.fenceToken(), 2L, "rep-takeover", "new", null, "takeover fix");

        // 新命令继承接管后的租约与令牌
        assertThat(newCommand.leaseId()).isEqualTo(next.leaseId());
        assertThat(newCommand.fenceToken()).isEqualTo(next.fenceToken());
        assertThat(newCommand.acceptOrder()).isEqualTo(2L);

        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline.get(0).state()).isEqualTo(CommandState.REPLACED);
        assertThat(timeline.get(1).commandUuid()).isEqualTo(newCommand.commandUuid());
        assertThat(timeline.get(1).leaseId()).isEqualTo(next.leaseId());
    }

    @Test
    void lateReceiptAfterReplaceIsKeptAsAnomalyWithoutChangingState() {
        CommandView oldCommand = submit(1L, "k-old");
        CommandView newCommand = replace(oldCommand, 2L, "rep-late-receipt", "new");

        // 旧命令的迟到成功回执只记异常，不复活旧命令
        ReceiptView late = receiptService.report(device, oldCommand.commandUuid(),
                lease.leaseId(), lease.fenceToken(),
                "late-success-replace", ReceiptKind.SUCCEEDED, "ok");

        assertThat(late.anomalous()).isTrue();

        List<CommandView> timeline = commandService.timeline(device);
        CommandView oldView = timeline.get(0);
        assertThat(oldView.state()).isEqualTo(CommandState.REPLACED);
        assertThat(oldView.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.eventId()).isEqualTo("late-success-replace");
            assertThat(receipt.anomalous()).isTrue();
        });
        // 新命令不受旧命令迟到回执影响
        assertThat(timeline.get(1).commandUuid()).isEqualTo(newCommand.commandUuid());
        assertThat(timeline.get(1).state()).isEqualTo(CommandState.PENDING);
    }

    @Test
    void replacementCommandIsIndependentlyExecutable() {
        CommandView oldCommand = submit(1L, "k-old");
        CommandView newCommand = replace(oldCommand, 2L, "rep-exec-new", "new");

        commandService.dispatch(device, newCommand.commandUuid());
        receiptService.report(device, newCommand.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "ack-rep-new", ReceiptKind.ACK, "started");
        receiptService.report(device, newCommand.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "done-rep-new", ReceiptKind.SUCCEEDED, "ok");

        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline.get(0).state()).isEqualTo(CommandState.REPLACED);
        assertThat(timeline.get(1).state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(timeline.get(1).dispatchedAt()).isNotNull();
    }

    @Test
    void timelineClearlyDistinguishesCancelledTimedOutReplacedAndExecuted() {
        CommandView cancelled = submit(1L, "k-cancel");
        cancelService.cancel(device, cancelled.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "crep-mix", null);

        CommandView timedOut = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                2L, "k-timeout", "payload", clock.instant());
        timeoutService.scanDevice(device);

        CommandView replacedOld = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                3L, "k-replaced", "old");
        replace(replacedOld, 4L, "rep-mix", "new");

        CommandView executed = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                5L, "k-exec", "payload");
        receiptService.report(device, executed.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "done-rep-exec", ReceiptKind.SUCCEEDED, "ok");

        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline).hasSize(5);

        assertThat(timeline.get(0).state()).isEqualTo(CommandState.CANCELLED);
        assertThat(timeline.get(0).cancel()).isNotNull();
        assertThat(timeline.get(0).replacement()).isNull();

        assertThat(timeline.get(1).state()).isEqualTo(CommandState.TIMED_OUT);
        assertThat(timeline.get(1).cancel()).isNotNull();
        assertThat(timeline.get(1).replacement()).isNull();

        assertThat(timeline.get(2).state()).isEqualTo(CommandState.REPLACED);
        assertThat(timeline.get(2).cancel()).isNull();
        assertThat(timeline.get(2).replacement()).isNotNull();
        assertThat(timeline.get(2).replacement().replacementCommandUuid())
                .isEqualTo(timeline.get(3).commandUuid());

        assertThat(timeline.get(3).state()).isEqualTo(CommandState.PENDING);
        assertThat(timeline.get(3).replacement()).isNotNull();
        assertThat(timeline.get(3).replacement().oldCommandUuid())
                .isEqualTo(timeline.get(2).commandUuid());

        assertThat(timeline.get(4).state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(timeline.get(4).cancel()).isNull();
        assertThat(timeline.get(4).replacement()).isNull();
    }

    @Test
    void concurrentReplaceAndDeviceAckSettleInExactlyOneOutcome() throws Exception {
        CommandView oldCommand = submit(1L, "k-old");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Boolean> replaceWon = pool.submit((Callable<Boolean>) () -> {
            start.await();
            try {
                replaceService.replace(device, oldCommand.commandUuid(), lease.leaseId(),
                        lease.fenceToken(), 2L, "rep-race", "new", null, null);
                return true;
            } catch (ApiException ex) {
                if ("command_not_replaceable".equals(ex.getCode())) {
                    return false;
                }
                throw ex;
            }
        });
        Future<Boolean> ackWon = pool.submit((Callable<Boolean>) () -> {
            start.await();
            try {
                receiptService.report(device, oldCommand.commandUuid(), lease.leaseId(),
                        lease.fenceToken(), "ack-rep-race", ReceiptKind.ACK, "started");
                return true;
            } catch (ApiException ex) {
                return false;
            }
        });

        start.countDown();
        boolean replaced = replaceWon.get(30, TimeUnit.SECONDS);
        ackWon.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        List<CommandView> timeline = commandService.timeline(device);
        CommandView oldView = timeline.get(0);
        if (replaced) {
            // 替换胜出：旧命令进入 REPLACED，设备迟到 ACK 只记异常
            assertThat(oldView.state()).isEqualTo(CommandState.REPLACED);
            assertThat(oldView.replacement()).isNotNull();
            assertThat(timeline).hasSize(2);
            assertThat(timeline.get(1).state()).isEqualTo(CommandState.PENDING);
            assertThat(oldView.receipts()).singleElement().satisfies(receipt -> {
                assertThat(receipt.kind()).isEqualTo(ReceiptKind.ACK);
                assertThat(receipt.anomalous()).isTrue();
            });
        } else {
            // 设备开始胜出：旧命令进入 ACKNOWLEDGED，替换被拒绝，无新命令产生
            assertThat(oldView.state()).isEqualTo(CommandState.ACKNOWLEDGED);
            assertThat(oldView.replacement()).isNull();
            assertThat(timeline).hasSize(1);
            assertThat(oldView.receipts()).singleElement()
                    .extracting(ReceiptView::anomalous).isEqualTo(false);
        }
    }
}
