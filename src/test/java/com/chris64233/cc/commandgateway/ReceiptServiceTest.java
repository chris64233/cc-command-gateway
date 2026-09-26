package com.chris64233.cc.commandgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.domain.ReceiptKind;
import com.chris64233.cc.commandgateway.service.CommandService;
import com.chris64233.cc.commandgateway.service.DeviceService;
import com.chris64233.cc.commandgateway.service.LeaseService;
import com.chris64233.cc.commandgateway.service.ReceiptService;
import com.chris64233.cc.commandgateway.support.MutableClock;
import com.chris64233.cc.commandgateway.support.TestClockConfig;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;

@SpringBootTest
@Import(TestClockConfig.class)
class ReceiptServiceTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private LeaseService leaseService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private ReceiptService receiptService;
    @Autowired
    private MutableClock clock;

    private String device;
    private LeaseView lease;
    private CommandView command;

    @BeforeEach
    void setUp() {
        device = "dev-receipt-" + java.util.UUID.randomUUID();
        deviceService.register(device);
        lease = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofHours(1)));
        command = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                1L, "cmd-1", "payload");
    }

    @Test
    void receiptsCanArriveOutOfOrderAndAttachToTimeline() {
        CommandView other = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                2L, "cmd-2", "payload");

        receiptService.report(device, other.commandUuid(), lease.leaseId(), lease.fenceToken(),
                "e-2", ReceiptKind.SUCCEEDED, "done");
        receiptService.report(device, command.commandUuid(), lease.leaseId(), lease.fenceToken(),
                "e-1", ReceiptKind.ACK, "seen");

        java.util.List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline.get(0).state()).isEqualTo(CommandState.ACKNOWLEDGED);
        assertThat(timeline.get(1).state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(timeline.get(1).receipts()).singleElement()
                .extracting(ReceiptView::eventId).isEqualTo("e-2");
    }

    @Test
    void duplicateReceiptEventReplaysFirstRecord() {
        ReceiptView first = receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "evt-1", ReceiptKind.ACK, "note");
        ReceiptView replay = receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "evt-1", ReceiptKind.ACK, "note");

        assertThat(replay).isEqualTo(first);
        assertThat(commandService.timeline(device).get(0).receipts()).hasSize(1);
    }

    @Test
    void sameReceiptEventIdWithDifferentContentConflicts() {
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "evt-x", ReceiptKind.ACK, "note-a");

        assertThatThrownBy(() -> receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "evt-x", ReceiptKind.ACK, "note-b"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("receipt_event_conflict");
    }

    @Test
    void sameEventIdForDifferentCommandConflicts() {
        CommandView other = commandService.submit(device, lease.leaseId(), lease.fenceToken(),
                2L, "cmd-2", "payload");
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "evt-shared", ReceiptKind.ACK, "note");

        assertThatThrownBy(() -> receiptService.report(device, other.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "evt-shared", ReceiptKind.ACK, "note"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("receipt_event_conflict");
    }

    @Test
    void terminalReceiptCannotBeOverwrittenByAnotherTerminal() {
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "term-1", ReceiptKind.SUCCEEDED, "ok");

        assertThatThrownBy(() -> receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "term-2", ReceiptKind.FAILED, "boom"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("terminal_receipt");

        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.SUCCEEDED);
    }

    @Test
    void lateAckAfterTerminalIsRejected() {
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "term-3", ReceiptKind.FAILED, "boom");

        assertThatThrownBy(() -> receiptService.report(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "late-ack", ReceiptKind.ACK, "seen"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("terminal_receipt");
    }

    @Test
    void unknownCommandRejected() {
        assertThatThrownBy(() -> receiptService.report(device, "00000000-0000-0000-0000-000000000000",
                lease.leaseId(), lease.fenceToken(), "evt-u", ReceiptKind.ACK, "note"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("not_found");
    }

    @Test
    void receiptWithStaleFenceTokenRejected() {
        clock.advance(Duration.ofMinutes(61));
        LeaseView next = leaseService.acquire(device, "client-b",
                clock.instant().plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> receiptService.report(device, command.commandUuid(),
                next.leaseId(), next.fenceToken(), "evt-stale", ReceiptKind.ACK, "note"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("fence_token_mismatch");
    }

    @Test
    void acknowledgedCommandCanStillReachTerminal() {
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "ack-1", ReceiptKind.ACK, "seen");
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "succ-1", ReceiptKind.SUCCEEDED, "done");

        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.SUCCEEDED);
    }
}
