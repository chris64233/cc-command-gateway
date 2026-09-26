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
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;

@SpringBootTest
@Import(TestClockConfig.class)
class CommandDispatchTest {

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
        device = "dev-dispatch-" + UUID.randomUUID();
        deviceService.register(device);
        lease = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofHours(1)));
    }

    private CommandView submit(long seq, String key) {
        return commandService.submit(device, lease.leaseId(), lease.fenceToken(), seq, key, "payload");
    }

    @Test
    void dispatchPendingCommandMarksDispatched() {
        CommandView command = submit(1L, "k1");

        CommandView dispatched = commandService.dispatch(device, command.commandUuid());

        assertThat(dispatched.state()).isEqualTo(CommandState.DISPATCHED);
        assertThat(dispatched.dispatchedAt()).isEqualTo(clock.instant());
        assertThat(commandService.timeline(device).get(0).state())
                .isEqualTo(CommandState.DISPATCHED);
    }

    @Test
    void repeatedDispatchIsIdempotent() {
        CommandView command = submit(1L, "k1");
        CommandView first = commandService.dispatch(device, command.commandUuid());

        clock.advance(Duration.ofMinutes(5));
        CommandView again = commandService.dispatch(device, command.commandUuid());

        assertThat(again.state()).isEqualTo(CommandState.DISPATCHED);
        assertThat(again.dispatchedAt()).isEqualTo(first.dispatchedAt());
    }

    @Test
    void dispatchUnknownCommandReturnsNotFound() {
        assertThatThrownBy(() -> commandService.dispatch(device,
                "00000000-0000-0000-0000-000000000000"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("not_found");
    }

    @Test
    void dispatchCancelledCommandRejected() {
        CommandView command = submit(1L, "k1");
        cancelService.cancel(device, command.commandUuid(),
                lease.leaseId(), lease.fenceToken(), "cancel-before-dispatch", null);

        assertThatThrownBy(() -> commandService.dispatch(device, command.commandUuid()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_dispatchable");
    }

    @Test
    void dispatchAcknowledgedCommandRejected() {
        CommandView command = submit(1L, "k1");
        commandService.dispatch(device, command.commandUuid());
        receiptService.report(device, command.commandUuid(), lease.leaseId(),
                lease.fenceToken(), "ack-dispatch-test", ReceiptKind.ACK, "started");

        assertThatThrownBy(() -> commandService.dispatch(device, command.commandUuid()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("command_not_dispatchable");
    }
}
