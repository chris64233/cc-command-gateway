package com.chris64233.cc.commandgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import com.chris64233.cc.commandgateway.web.dto.CancelView;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;
import com.chris64233.cc.commandgateway.web.dto.ReplaceResultView;

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
        return commandService.submit(device, lease.leaseId(), lease.fenceToken(), seq, key, "old-payload");
    }

    private ReplaceResultView replace(CommandView oldCommand, long seq, String newKey,
                                      String replaceId, String payload) {
        return replaceService.replace(device, oldCommand.commandUuid(),
                lease.leaseId(), lease.fenceToken(), seq, newKey, payload,
                null, replaceId, "replace reason");
    }

    private ReplaceResultView replace(CommandView oldCommand, long seq, String newKey,
                                      String replaceId) {
        return replace(oldCommand, seq, newKey, replaceId, "new-payload");
    }

    // ---- 需求 1：旧命令进入已替换状态，新命令继承设备和业务上下文 ----

    @Test
    void replacingPendingCommandMarksOldReplacedAndAcceptsNewWithContext() {
        Instant deadline = clock.instant().plus(Duration.ofMinutes(30));
        CommandView oldCommand = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                1L, "old-key", "old-payload", deadline);

        ReplaceResultView result = replace(oldCommand, 2L, "new-key", "replace-1");

        // 新命令继承设备与租约业务上下文
        CommandView fresh = result.newCommand();
        assertThat(fresh.deviceId()).isEqualTo(device);
        assertThat(fresh.leaseId()).isEqualTo(lease.leaseId());
        assertThat(fresh.fenceToken()).isEqualTo(lease.fenceToken());
        assertThat(fresh.clientSeq()).isEqualTo(2L);
        assertThat(fresh.idempotencyKey()).isEqualTo("new-key");
        assertThat(fresh.payload()).isEqualTo("new-payload");
        assertThat(fresh.acceptOrder()).isEqualTo(2L);
        assertThat(fresh.state()).isEqualTo(CommandState.PENDING);
        // deadlineAt 缺省继承旧命令
        assertThat(fresh.deadlineAt()).isEqualTo(deadline);
        assertThat(fresh.acceptedAt()).isEqualTo(clock.instant());
        assertThat(fresh.replacementOf().replaceId()).isEqualTo("replace-1");
        assertThat(fresh.replacementOf().oldCommandUuid()).isEqualTo(oldCommand.commandUuid());
        assertThat(fresh.replacedBy()).isNull();

        assertThat(result.replacement().newCommandUuid()).isEqualTo(fresh.commandUuid());
        assertThat(result.replacement().oldCommandUuid()).isEqualTo(oldCommand.commandUuid());
        assertThat(result.replacement().replacedAt()).isEqualTo(clock.instant());

        // 旧命令保留可追溯结束状态，且指向新命令；时间线在同一提交后可见两条记录
        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline).hasSize(2);
        CommandView oldView = timeline.get(0);
        assertThat(oldView.state()).isEqualTo(CommandState.REPLACED);
        assertThat(oldView.acceptOrder()).isEqualTo(1L);
        assertThat(oldView.replacedBy().newCommandUuid()).isEqualTo(fresh.commandUuid());
        assertThat(oldView.replacedBy().replaceId()).isEqualTo("replace-1");
        assertThat(oldView.replacementOf()).isNull();
        assertThat(oldView.cancel()).isNull();

        CommandView newView = timeline.get(1);
        assertThat(newView.commandUuid()).isEqualTo(fresh.commandUuid());
        assertThat(newView.replacementOf().oldCommandUuid()).isEqualTo(oldCommand.commandUuid());
    }

    @Test
    void replacingDispatchedCommandKeepsOldDispatchedAtForTrace() {
        CommandView oldCommand = submit(1L, "old-key");
        commandService.dispatch(device, oldCommand.commandUuid());

        ReplaceResultView result = replace(oldCommand, 2L, "new-key", "replace-dispatched");

        CommandView oldView = commandService.timeline(device).get(0);
        assertThat(oldView.state()).isEqualTo(CommandState.REPLACED);
        assertThat(oldView.dispatchedAt()).isNotNull();
        assertThat(result.newCommand().state()).isEqualTo(CommandState.PENDING);
        assertThat(result.newCommand().dispatchedAt()).isNull();
    }

    @Test
    void replacementAdvancesLeaseSequenceAndDeviceAcceptOrder() {
        CommandView oldCommand = submit(1L, "old-key");
        replace(oldCommand, 2L, "new-key", "replace-seq");

        // 替换是一次新的接受，占用租约序号
        assertThatThrownBy(() -> submit(2L, "another-key"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("stale_client_seq");

        CommandView after = submit(3L, "third-key");
        assertThat(after.acceptOrder()).isEqualTo(3L);
    }

    @Test
    void replacementCanSpecifyOwnDeadlineInsteadOfInheriting() {
        CommandView oldCommand = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                1L, "old-key", "old-payload", clock.instant().plus(Duration.ofMinutes(10)));
        Instant newDeadline = clock.instant().plus(Duration.ofMinutes(90));

        ReplaceResultView result = replaceService.replace(device, oldCommand.commandUuid(),
                lease.leaseId(), lease.fenceToken(), 2L, "new-key", "new-payload",
                newDeadline, "replace-deadline", null);

        assertThat(result.newCommand().deadlineAt()).isEqualTo(newDeadline);
    }

    // ---- 可替换状态范围 ----

    @Test
    void acknowledgedCommandCannotBeReplaced() {
        CommandView oldCommand = submit(1L, "old-key");
        receiptService.report(device, oldCommand.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "ack-replace", ReceiptKind.ACK, "started");

        assertThatThrownBy(() -> replace(oldCommand, 2L, "new-key", "replace-too-late"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");

        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).state()).isEqualTo(CommandState.ACKNOWLEDGED);
    }

    @Test
    void terminalCommandsCannotBeReplaced() {
        CommandView succeeded = submit(1L, "key-done");
        receiptService.report(device, succeeded.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "rep-done", ReceiptKind.SUCCEEDED, "ok");
        assertThatThrownBy(() -> replace(succeeded, 2L, "new-1", "r-done"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");

        CommandView cancelled = submit(3L, "key-cancel");
        cancelService.cancel(device, cancelled.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "cancel-before-replace", null);
        assertThatThrownBy(() -> replace(cancelled, 4L, "new-2", "r-cancelled"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");

        CommandView timedOut = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                5L, "key-timeout", "p", clock.instant());
        clock.advance(Duration.ofMinutes(1));
        List<CancelView> scanned = timeoutService.scanDevice(device);
        assertThat(scanned).hasSize(1);
        clock.advance(Duration.ofMinutes(-1));
        assertThatThrownBy(() -> replace(timedOut, 6L, "new-3", "r-timed-out"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");

        // 所有被拒替换都没有产生新命令：仍是最初 3 条
        assertThat(commandService.timeline(device)).hasSize(3);
    }

    @Test
    void replacedCommandCannotBeReplacedAgainButNewCommandCan() {
        CommandView first = submit(1L, "old-key");
        ReplaceResultView firstReplacement = replace(first, 2L, "new-key-1", "replace-chain-1");

        assertThatThrownBy(() -> replace(first, 3L, "new-key-2", "replace-chain-2"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");

        // 新命令本身仍可被继续替换（链式替换）
        ReplaceResultView chained = replace(firstReplacement.newCommand(), 3L,
                "new-key-2", "replace-chain-2");
        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline).extracting(CommandView::state)
                .containsExactly(CommandState.REPLACED, CommandState.REPLACED, CommandState.PENDING);
        assertThat(timeline.get(0).replacedBy().newCommandUuid())
                .isEqualTo(firstReplacement.newCommand().commandUuid());
        assertThat(timeline.get(1).replacedBy().newCommandUuid())
                .isEqualTo(chained.newCommand().commandUuid());
        assertThat(timeline.get(2).replacementOf().oldCommandUuid())
                .isEqualTo(firstReplacement.newCommand().commandUuid());
    }

    // ---- 需求 3：外部请求号幂等，同号不同内容冲突 ----

    @Test
    void replaceReplayWithSameReplaceIdReturnsFirstResult() {
        CommandView oldCommand = submit(1L, "old-key");

        ReplaceResultView first = replace(oldCommand, 2L, "new-key", "replace-idem");
        ReplaceResultView replay = replace(oldCommand, 2L, "new-key", "replace-idem");

        assertThat(replay.replacement()).isEqualTo(first.replacement());
        assertThat(replay.newCommand()).isEqualTo(first.newCommand());
        // 不重复创建命令与替换关系
        assertThat(commandService.timeline(device)).hasSize(2);
    }

    @Test
    void replaceIdReplayWithoutDeadlineInheritsSameDeadlineAndStaysIdempotent() {
        Instant deadline = clock.instant().plus(Duration.ofMinutes(20));
        CommandView oldCommand = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                1L, "old-key", "old", deadline);

        ReplaceResultView first = replace(oldCommand, 2L, "new-key", "replace-inherit");
        clock.advance(Duration.ofMinutes(5));
        ReplaceResultView replay = replace(oldCommand, 2L, "new-key", "replace-inherit");

        assertThat(replay).isEqualTo(first);
        assertThat(replay.newCommand().deadlineAt()).isEqualTo(deadline);
        assertThat(replay.replacement().replacedAt()).isEqualTo(first.replacement().replacedAt());
    }

    @Test
    void sameReplaceIdWithDifferentPayloadConflicts() {
        CommandView oldCommand = submit(1L, "old-key");
        ReplaceResultView first = replace(oldCommand, 2L, "new-key", "replace-shared");

        assertThatThrownBy(() -> replaceService.replace(device, oldCommand.commandUuid(),
                lease.leaseId(), lease.fenceToken(), 2L, "new-key", "different-payload",
                null, "replace-shared", "replace reason"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("replace_event_conflict");

        // 旧命令仍指向首次替换产生的新命令
        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline.get(0).state()).isEqualTo(CommandState.REPLACED);
        assertThat(timeline.get(0).replacedBy().newCommandUuid())
                .isEqualTo(first.newCommand().commandUuid());
        assertThat(timeline).hasSize(2);
    }

    @Test
    void sameReplaceIdWithDifferentSequenceConflicts() {
        CommandView oldCommand = submit(1L, "old-key");
        replace(oldCommand, 2L, "new-key", "replace-seq-conflict");

        assertThatThrownBy(() -> replace(oldCommand, 99L, "new-key", "replace-seq-conflict"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("replace_event_conflict");
    }

    @Test
    void sameReplaceIdBoundToDifferentCommandConflicts() {
        CommandView first = submit(1L, "old-key-1");
        CommandView second = submit(2L, "old-key-2");
        replace(first, 3L, "new-key-1", "replace-cross");

        assertThatThrownBy(() -> replace(second, 4L, "new-key-2", "replace-cross"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("replace_event_conflict");

        assertThat(commandService.timeline(device).get(1).state())
                .isEqualTo(CommandState.PENDING);
    }

    @Test
    void replacementRejectsDuplicateIdempotencyKey() {
        CommandView oldCommand = submit(1L, "old-key");
        replace(oldCommand, 2L, "new-key", "replace-guard");

        // 旧命令已被替换（不再可替换），重复替换先报状态冲突
        assertThatThrownBy(() -> replace(oldCommand, 3L, "other-new-key", "r-old-gone"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_replaceable");

        CommandView another = submit(3L, "another-old");
        // 新命令的幂等键在设备上必须唯一
        assertThatThrownBy(() -> replace(another, 4L, "new-key", "r-dup-key"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("idempotency_conflict");
        assertThat(commandService.timeline(device)).hasSize(3);
    }

    @Test
    void replacementRequiresCurrentLeaseAndFenceToken() {
        CommandView oldCommand = submit(1L, "old-key");

        assertThatThrownBy(() -> replaceService.replace(device, oldCommand.commandUuid(),
                lease.leaseId(), lease.fenceToken() + 1, 2L, "new-key", "p",
                null, "r-bad-token", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("fence_token_mismatch");

        clock.advance(Duration.ofMinutes(61));
        LeaseView expiredLease = lease;
        assertThatThrownBy(() -> replaceService.replace(device, oldCommand.commandUuid(),
                expiredLease.leaseId(), expiredLease.fenceToken(), 2L, "new-key-2", "p",
                null, "r-expired", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("lease_expired");
    }

    @Test
    void newLeaseWithLargerFenceTokenCanReplaceCommandOfOldLease() {
        CommandView oldCommand = submit(1L, "old-key");
        clock.advance(Duration.ofMinutes(61));
        LeaseView next = leaseService.acquire(device, "client-b",
                clock.instant().plus(Duration.ofHours(1)));

        ReplaceResultView result = replaceService.replace(device, oldCommand.commandUuid(),
                next.leaseId(), next.fenceToken(), 2L, "new-key", "new-payload",
                null, "replace-takeover", "new owner correction");

        assertThat(result.newCommand().leaseId()).isEqualTo(next.leaseId());
        assertThat(result.newCommand().fenceToken()).isEqualTo(next.fenceToken());
        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.REPLACED);
    }

    // ---- 需求 2：替换与设备开始并发只能一个成功 ----

    @Test
    void concurrentReplaceAndDeviceAckSettleInExactlyOneOutcome() throws Exception {
        CommandView oldCommand = submit(1L, "old-key");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Boolean> replaceWon = pool.submit(() -> {
            start.await();
            try {
                replace(oldCommand, 2L, "new-key", "replace-race");
                return true;
            } catch (ApiException ex) {
                if ("command_not_replaceable".equals(ex.getCode())) {
                    return false;
                }
                throw ex;
            }
        });
        Future<Boolean> ackReported = pool.submit(() -> {
            start.await();
            receiptService.report(device, oldCommand.commandUuid(), lease.leaseId(),
                    lease.fenceToken(), "rep-ack-race", ReceiptKind.ACK, "started");
            return true;
        });

        start.countDown();
        boolean replaced = replaceWon.get(30, TimeUnit.SECONDS);
        ackReported.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        List<CommandView> timeline = commandService.timeline(device);
        if (replaced) {
            // 替换胜出：设备开始的迟到回执只记异常，旧命令保持已替换
            assertThat(timeline).hasSize(2);
            CommandView oldView = timeline.get(0);
            assertThat(oldView.state()).isEqualTo(CommandState.REPLACED);
            assertThat(oldView.receipts()).singleElement().satisfies(receipt -> {
                assertThat(receipt.kind()).isEqualTo(ReceiptKind.ACK);
                assertThat(receipt.anomalous()).isTrue();
            });
            assertThat(timeline.get(1).state()).isEqualTo(CommandState.PENDING);
        } else {
            // 设备开始胜出：指令进入执行阶段，替换被拒绝，没有产生新命令
            assertThat(timeline).hasSize(1);
            assertThat(timeline.get(0).state()).isEqualTo(CommandState.ACKNOWLEDGED);
            assertThat(timeline.get(0).replacedBy()).isNull();
            assertThat(timeline.get(0).receipts()).singleElement()
                    .satisfies(receipt -> assertThat(receipt.anomalous()).isFalse());
        }
    }

    // ---- 需求 2：旧命令迟到回执只记异常 ----

    @Test
    void lateReceiptsForReplacedCommandAreKeptAsAnomalies() {
        CommandView oldCommand = submit(1L, "old-key");
        ReplaceResultView result = replace(oldCommand, 2L, "new-key", "replace-late");

        ReceiptView lateAck = receiptService.report(device, oldCommand.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "rep-late-ack", ReceiptKind.ACK, "started");
        ReceiptView lateSuccess = receiptService.report(device, oldCommand.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "rep-late-success", ReceiptKind.SUCCEEDED, "ok");

        assertThat(lateAck.anomalous()).isTrue();
        assertThat(lateSuccess.anomalous()).isTrue();

        CommandView oldView = commandService.timeline(device).get(0);
        assertThat(oldView.state()).isEqualTo(CommandState.REPLACED);
        assertThat(oldView.receipts()).extracting(ReceiptView::anomalous)
                .containsExactly(true, true);

        // 新命令不受旧命令迟到回执影响
        CommandView newView = commandService.timeline(device).get(1);
        assertThat(newView.commandUuid()).isEqualTo(result.newCommand().commandUuid());
        assertThat(newView.state()).isEqualTo(CommandState.PENDING);
        assertThat(newView.receipts()).isEmpty();
    }

    // ---- 需求 4/5：关系与时间线同事务提交，查询区分各终态 ----

    @Test
    void timelineKeepsCancellationTimeoutReplacementAndExecutionDistinguishable() {
        CommandView cancelled = submit(1L, "key-cancel");
        CommandView timedOut = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                2L, "key-timeout", "p", clock.instant());
        CommandView replaced = submit(3L, "key-replaced");
        CommandView succeeded = submit(4L, "key-success");
        CommandView failed = submit(5L, "key-failed");
        submit(6L, "key-pending");

        cancelService.cancel(device, cancelled.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "rep-cancel-1", null);
        clock.advance(Duration.ofMinutes(1));
        List<CancelView> scanned = timeoutService.scanDevice(device);
        assertThat(scanned).hasSize(1);

        ReplaceResultView replacement = replace(replaced, 7L, "key-replacement", "replace-q");
        receiptService.report(device, succeeded.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "rep-evt-success", ReceiptKind.SUCCEEDED, "ok");
        receiptService.report(device, failed.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "rep-evt-failed", ReceiptKind.FAILED, "boom");

        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline).extracting(CommandView::state).containsExactly(
                CommandState.CANCELLED,
                CommandState.TIMED_OUT,
                CommandState.REPLACED,
                CommandState.SUCCEEDED,
                CommandState.FAILED,
                CommandState.PENDING,
                CommandState.PENDING);

        CommandView cancelView = timeline.get(0);
        assertThat(cancelView.cancel()).isNotNull();
        assertThat(cancelView.replacedBy()).isNull();

        CommandView timeoutView = timeline.get(1);
        assertThat(timeoutView.cancel()).isNotNull();
        assertThat(timeoutView.replacedBy()).isNull();

        CommandView replacedView = timeline.get(2);
        assertThat(replacedView.cancel()).isNull();
        assertThat(replacedView.replacedBy().newCommandUuid())
                .isEqualTo(replacement.newCommand().commandUuid());

        CommandView succeededView = timeline.get(3);
        assertThat(succeededView.cancel()).isNull();
        assertThat(succeededView.replacedBy()).isNull();
        assertThat(succeededView.receipts()).singleElement()
                .satisfies(receipt -> assertThat(receipt.anomalous()).isFalse());

        CommandView newCommandView = timeline.get(6);
        assertThat(newCommandView.replacementOf().oldCommandUuid())
                .isEqualTo(replaced.commandUuid());
    }
}
