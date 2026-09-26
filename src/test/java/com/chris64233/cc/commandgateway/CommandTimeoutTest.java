package com.chris64233.cc.commandgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

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

@SpringBootTest
@Import(TestClockConfig.class)
class CommandTimeoutTest {

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
    private long seq;
    private String run;

    @BeforeEach
    void setUp() {
        run = UUID.randomUUID().toString().substring(0, 8);
        device = "dev-timeout-" + UUID.randomUUID();
        deviceService.register(device);
        lease = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofHours(4)));
        seq = 0;
    }

    /** 回执事件号与取消号全局唯一，测试内加随机后缀避免相互污染。 */
    private String id(String base) {
        return base + "-" + run;
    }

    private CommandView submit(String key, Duration ttl) {
        seq++;
        return commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                seq, key, "payload", ttl == null ? null : clock.instant().plus(ttl));
    }

    private CommandView timelineCommand(int index) {
        return commandService.timeline(device).get(index);
    }

    @Test
    void submitWithPastDeadlineRejected() {
        assertThatThrownBy(() -> commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                1L, "cmd-past", "payload", clock.instant().minusSeconds(1)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("deadline_in_past");
        assertThat(commandService.timeline(device)).isEmpty();
    }

    @Test
    void expiredCommandIsTimedOutByScan() {
        CommandView command = submit("cmd-1", Duration.ofMinutes(30));
        assertThat(command.deadlineAt()).isNotNull();

        clock.advance(Duration.ofMinutes(31));
        List<CommandView> timedOut = lifecycleService.scanTimeouts(device);

        assertThat(timedOut).hasSize(1);
        assertThat(timedOut.get(0).commandUuid()).isEqualTo(command.commandUuid());
        assertThat(timedOut.get(0).state()).isEqualTo(CommandState.TIMED_OUT);

        CommandView persisted = timelineCommand(0);
        assertThat(persisted.state()).isEqualTo(CommandState.TIMED_OUT);
        assertThat(persisted.events().stream().map(CommandEventView::kind))
                .containsExactly(CommandEventKind.ACCEPTED, CommandEventKind.TIMED_OUT);
        assertThat(persisted.events().get(1).eventId())
                .isEqualTo("timeout:" + command.commandUuid());
    }

    @Test
    void scanIsSafelyRetryableWithoutDuplicateTimeoutEvents() {
        submit("cmd-1", Duration.ofMinutes(30));

        clock.advance(Duration.ofMinutes(31));
        assertThat(lifecycleService.scanTimeouts(device)).hasSize(1);
        assertThat(lifecycleService.scanTimeouts(device)).isEmpty();
        assertThat(lifecycleService.scanTimeouts(device)).isEmpty();

        assertThat(timelineCommand(0).events().stream()
                .filter(event -> event.kind() == CommandEventKind.TIMED_OUT))
                .hasSize(1);
    }

    @Test
    void scanOnlyPicksUpCommandsPastDeadline() {
        CommandView expired = submit("cmd-expired", Duration.ofMinutes(10));
        CommandView alive = submit("cmd-alive", Duration.ofHours(2));
        CommandView noDeadline = submit("cmd-no-deadline", null);

        clock.advance(Duration.ofMinutes(30));
        List<CommandView> timedOut = lifecycleService.scanTimeouts(device);

        assertThat(timedOut).hasSize(1);
        assertThat(timedOut.get(0).commandUuid()).isEqualTo(expired.commandUuid());
        assertThat(timelineCommand(1).state()).isEqualTo(CommandState.PENDING);
        assertThat(timelineCommand(1).commandUuid()).isEqualTo(alive.commandUuid());
        assertThat(timelineCommand(2).commandUuid()).isEqualTo(noDeadline.commandUuid());
        assertThat(timelineCommand(2).state()).isEqualTo(CommandState.PENDING);
    }

    @Test
    void acknowledgedCommandIsNotTimedOut() {
        CommandView command = submit("cmd-1", Duration.ofMinutes(30));
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), id("ack-1"), ReceiptKind.ACK, "started");

        clock.advance(Duration.ofMinutes(31));
        assertThat(lifecycleService.scanTimeouts(device)).isEmpty();
        assertThat(timelineCommand(0).state()).isEqualTo(CommandState.ACKNOWLEDGED);
    }

    @Test
    void cancelledCommandIsNotTimedOut() {
        CommandView command = submit("cmd-1", Duration.ofMinutes(30));
        lifecycleService.cancel(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), id("cancel-1"), "abort");

        clock.advance(Duration.ofMinutes(31));
        assertThat(lifecycleService.scanTimeouts(device)).isEmpty();
        assertThat(timelineCommand(0).state()).isEqualTo(CommandState.CANCELLED);
    }

    @Test
    void cancelAfterTimeoutReportsTerminalConflict() {
        CommandView command = submit("cmd-1", Duration.ofMinutes(30));

        clock.advance(Duration.ofMinutes(31));
        lifecycleService.scanTimeouts(device);

        assertThatThrownBy(() -> lifecycleService.cancel(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), id("cancel-too-late"), "abort"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_already_terminal");
        assertThat(timelineCommand(0).state()).isEqualTo(CommandState.TIMED_OUT);
    }

    @Test
    void lateSuccessReceiptAfterTimeoutKeptAsAnomaly() {
        CommandView command = submit("cmd-1", Duration.ofMinutes(30));

        clock.advance(Duration.ofMinutes(31));
        lifecycleService.scanTimeouts(device);

        var late = receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), id("late-success"), ReceiptKind.SUCCEEDED, "done");

        assertThat(late.late()).isTrue();
        CommandView persisted = timelineCommand(0);
        assertThat(persisted.state()).isEqualTo(CommandState.TIMED_OUT);
        assertThat(persisted.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.eventId()).isEqualTo(id("late-success"));
            assertThat(receipt.late()).isTrue();
        });
    }

    @Test
    void dispatchedCommandPastDeadlineIsTimedOut() {
        CommandView command = submit("cmd-1", Duration.ofMinutes(30));
        lifecycleService.dispatch(device, command.commandUuid());

        clock.advance(Duration.ofMinutes(31));
        List<CommandView> timedOut = lifecycleService.scanTimeouts(device);

        assertThat(timedOut).hasSize(1);
        assertThat(timelineCommand(0).events().stream().map(CommandEventView::kind))
                .containsExactly(CommandEventKind.ACCEPTED, CommandEventKind.DISPATCHED,
                        CommandEventKind.TIMED_OUT);
    }
}
