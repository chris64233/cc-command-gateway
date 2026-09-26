package com.chris64233.cc.commandgateway.service;

import com.chris64233.cc.commandgateway.domain.CommandStatus;
import com.chris64233.cc.commandgateway.web.dto.AcquireLeaseRequest;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptRequest;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;
import com.chris64233.cc.commandgateway.web.dto.SubmitCommandRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ReceiptServiceTest {

    @Autowired
    private DeviceGatewayService service;

    private CommandView acceptedCommand(String deviceNumber) {
        service.registerDevice(deviceNumber);
        LeaseView lease = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 60_000L));
        return service.submitCommand(deviceNumber, new SubmitCommandRequest(
                lease.id(), lease.fencingToken(), 1, "k1", "payload"));
    }

    @Test
    void receiptsMayArriveOutOfOrderAndUpdateTheirAcceptedCommand() {
        String deviceNumber = "dev-rcpt-order";
        CommandView command = acceptedCommand(deviceNumber);
        long token = command.fencingToken();

        ReceiptView second = service.recordReceipt(deviceNumber, command.id(),
                new ReceiptRequest("evt-2", token, CommandStatus.RUNNING, "50%"));
        ReceiptView first = service.recordReceipt(deviceNumber, command.id(),
                new ReceiptRequest("evt-1", token, CommandStatus.RUNNING, "started"));

        assertThat(second.id()).isNotEqualTo(first.id());
        CommandView updated = service.getTimeline(deviceNumber).get(0);
        assertThat(updated.receipts()).hasSize(2);
        assertThat(updated.status()).isEqualTo(CommandStatus.RUNNING);
    }

    @Test
    void terminalReceiptCannotBeOverwrittenByAnotherTerminal() {
        String deviceNumber = "dev-rcpt-terminal";
        CommandView command = acceptedCommand(deviceNumber);
        long token = command.fencingToken();

        service.recordReceipt(deviceNumber, command.id(),
                new ReceiptRequest("evt-ok", token, CommandStatus.SUCCEEDED, "done"));

        assertThatThrownBy(() -> service.recordReceipt(deviceNumber, command.id(),
                new ReceiptRequest("evt-fail", token, CommandStatus.FAILED, "boom")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TERMINAL_STATE_LOCKED"));
        assertThat(service.getTimeline(deviceNumber).get(0).status())
                .isEqualTo(CommandStatus.SUCCEEDED);
    }

    @Test
    void duplicateReceiptEventIsIdempotentUnlessContentDiffers() {
        String deviceNumber = "dev-rcpt-dupe";
        CommandView command = acceptedCommand(deviceNumber);
        long token = command.fencingToken();

        ReceiptView original = service.recordReceipt(deviceNumber, command.id(),
                new ReceiptRequest("evt-1", token, CommandStatus.RUNNING, "half"));
        ReceiptView replay = service.recordReceipt(deviceNumber, command.id(),
                new ReceiptRequest("evt-1", token, CommandStatus.RUNNING, "half"));
        assertThat(replay.id()).isEqualTo(original.id());

        assertThatThrownBy(() -> service.recordReceipt(deviceNumber, command.id(),
                new ReceiptRequest("evt-1", token, CommandStatus.RUNNING, "different")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("RECEIPT_CONFLICT"));
        assertThatThrownBy(() -> service.recordReceipt(deviceNumber, command.id(),
                new ReceiptRequest("evt-1", token, CommandStatus.SUCCEEDED, "half")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("RECEIPT_CONFLICT"));
        assertThat(service.getTimeline(deviceNumber).get(0).receipts()).hasSize(1);
    }

    @Test
    void rejectsUnknownCommandAndMismatchedToken() {
        String deviceNumber = "dev-rcpt-unknown";
        CommandView command = acceptedCommand(deviceNumber);

        assertThatThrownBy(() -> service.recordReceipt(deviceNumber, 999_999L,
                new ReceiptRequest("evt-1", command.fencingToken(), CommandStatus.RUNNING, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COMMAND_NOT_FOUND"));

        assertThatThrownBy(() -> service.recordReceipt(deviceNumber, command.id(),
                new ReceiptRequest("evt-1", command.fencingToken() + 1, CommandStatus.RUNNING, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FENCING_TOKEN_MISMATCH"));
    }
}
