package com.chris64233.cc.commandgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import com.chris64233.cc.commandgateway.service.TimeoutService;
import com.chris64233.cc.commandgateway.support.MutableClock;
import com.chris64233.cc.commandgateway.support.TestClockConfig;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CancelView;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;

@SpringBootTest
@Import(TestClockConfig.class)
class TimeoutServiceTest {

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
    private MutableClock clock;

    private String device;
    private LeaseView lease;

    @BeforeEach
    void setUp() {
        device = "dev-timeout-" + UUID.randomUUID();
        deviceService.register(device);
        lease = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofHours(3)));
    }

    private CommandView submitWithDeadline(long seq, String key, Duration ttl) {
        return commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                seq, key, "payload", clock.instant().plus(ttl));
    }

    @Test
    void expiredPendingCommandIsTimedOut() {
        CommandView command = submitWithDeadline(1L, "k1", Duration.ofMinutes(30));
        clock.advance(Duration.ofMinutes(31));

        List<CancelView> cancelled = timeoutService.scanDevice(device);

        assertThat(cancelled).singleElement().satisfies(cancel -> {
            assertThat(cancel.kind()).isEqualTo(CancelKind.TIMEOUT);
            assertThat(cancel.cancelId())
                    .isEqualTo(CancelService.TIMEOUT_CANCEL_ID_PREFIX + command.commandUuid());
            assertThat(cancel.cancelledAt()).isEqualTo(clock.instant());
        });

        CommandView view = commandService.timeline(device).get(0);
        assertThat(view.state()).isEqualTo(CommandState.TIMED_OUT);
        assertThat(view.cancel().kind()).isEqualTo(CancelKind.TIMEOUT);
        assertThat(view.deadlineAt()).isNotNull();
    }

    @Test
    void scanIsRetrySafeAndDoesNotDuplicateEvents() {
        CommandView command = submitWithDeadline(1L, "k1", Duration.ofMinutes(30));
        clock.advance(Duration.ofMinutes(31));

        List<CancelView> first = timeoutService.scanDevice(device);
        List<CancelView> second = timeoutService.scanDevice(device);
        CancelView directRetry = cancelService.cancelTimedOut(command.commandUuid());

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        assertThat(directRetry.cancelId()).isEqualTo(first.get(0).cancelId());
        assertThat(commandService.timeline(device).get(0).cancel().cancelId())
                .isEqualTo(first.get(0).cancelId());
    }

    @Test
    void commandWithoutDeadlineIsNotScanned() {
        commandService.submit(device, lease.leaseId(), lease.fenceToken(), 1L, "k1", "payload");
        clock.advance(Duration.ofHours(2));

        assertThat(timeoutService.scanDevice(device)).isEmpty();
        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.PENDING);
    }

    @Test
    void futureDeadlineIsNotScanned() {
        submitWithDeadline(1L, "k1", Duration.ofHours(2));
        clock.advance(Duration.ofMinutes(30));

        assertThat(timeoutService.scanDevice(device)).isEmpty();
        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.PENDING);
    }

    @Test
    void dispatchedCommandIsTimedOut() {
        CommandView command = submitWithDeadline(1L, "k1", Duration.ofMinutes(30));
        commandService.dispatch(device, command.commandUuid());
        clock.advance(Duration.ofMinutes(31));

        assertThat(timeoutService.scanDevice(device)).hasSize(1);
        CommandView view = commandService.timeline(device).get(0);
        assertThat(view.state()).isEqualTo(CommandState.TIMED_OUT);
        assertThat(view.dispatchedAt()).isNotNull();
    }

    @Test
    void acknowledgedCommandIsNotTimedOut() {
        CommandView command = submitWithDeadline(1L, "k1", Duration.ofMinutes(30));
        commandService.dispatch(device, command.commandUuid());
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "ack-timeout-test", ReceiptKind.ACK, "started");
        clock.advance(Duration.ofMinutes(31));

        assertThat(timeoutService.scanDevice(device)).isEmpty();
        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.ACKNOWLEDGED);
    }

    @Test
    void succeededCommandIsNotTimedOut() {
        CommandView command = submitWithDeadline(1L, "k1", Duration.ofMinutes(30));
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "done-timeout-test", ReceiptKind.SUCCEEDED, "ok");
        clock.advance(Duration.ofMinutes(31));

        assertThat(timeoutService.scanDevice(device)).isEmpty();
        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.SUCCEEDED);
    }

    @Test
    void clientCancelledCommandIsNotRescanned() {
        CommandView command = submitWithDeadline(1L, "k1", Duration.ofMinutes(30));
        cancelService.cancel(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "cancel-before-scan", null);
        clock.advance(Duration.ofMinutes(31));

        assertThat(timeoutService.scanDevice(device)).isEmpty();

        CommandView view = commandService.timeline(device).get(0);
        assertThat(view.state()).isEqualTo(CommandState.CANCELLED);
        assertThat(view.cancel().kind()).isEqualTo(CancelKind.CLIENT);
    }

    @Test
    void lateReceiptAfterTimeoutIsKeptAsAnomalyWithoutChangingState() {
        CommandView command = submitWithDeadline(1L, "k1", Duration.ofMinutes(30));
        clock.advance(Duration.ofMinutes(31));
        timeoutService.scanDevice(device);

        ReceiptView late = receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "late-success-timeout-test", ReceiptKind.SUCCEEDED, "ok");

        assertThat(late.anomalous()).isTrue();
        CommandView view = commandService.timeline(device).get(0);
        assertThat(view.state()).isEqualTo(CommandState.TIMED_OUT);
        assertThat(view.receipts()).singleElement()
                .extracting(ReceiptView::anomalous).isEqualTo(true);
    }

    @Test
    void deadlineUsesUnifiedClockSource() {
        // 截止时间在“现在”之前的指令在提交后立即被扫描取消
        CommandView command = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                1L, "k1", "payload", Instant.now(clock));

        List<CancelView> cancelled = timeoutService.scanDevice(device);

        assertThat(cancelled).hasSize(1);
        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.TIMED_OUT);
    }

    @Test
    void scanUnknownDeviceReturnsNotFound() {
        assertThatThrownBy(() -> timeoutService.scanDevice("dev-missing-" + UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("not_found");
    }
}
