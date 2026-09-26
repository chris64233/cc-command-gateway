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

import com.chris64233.cc.commandgateway.service.CommandService;
import com.chris64233.cc.commandgateway.service.DeviceService;
import com.chris64233.cc.commandgateway.service.LeaseService;
import com.chris64233.cc.commandgateway.support.MutableClock;
import com.chris64233.cc.commandgateway.support.TestClockConfig;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;

@SpringBootTest
@Import(TestClockConfig.class)
class CommandSequencingServiceTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private LeaseService leaseService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private MutableClock clock;

    private String device;
    private LeaseView lease;

    @BeforeEach
    void setUp() {
        device = "dev-cmd-" + UUID.randomUUID();
        deviceService.register(device);
        lease = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofHours(1)));
    }

    private CommandView submit(long seq, String key, String payload) {
        return commandService.submit(device, lease.leaseId(), lease.fenceToken(), seq, key, payload);
    }

    @Test
    void acceptOrderAndClientSeqAdvanceTogether() {
        CommandView first = submit(10L, "k1", "open");
        CommandView second = submit(20L, "k2", "close");

        assertThat(first.acceptOrder()).isEqualTo(1L);
        assertThat(second.acceptOrder()).isEqualTo(2L);

        LeaseView current = leaseService.getCurrent(device);
        assertThat(current.lastAcceptedSeq()).isEqualTo(20L);
    }

    @Test
    void replaySameIdempotencyKeyReturnsFirstRecordWithoutAdvancingSeq() {
        CommandView first = submit(1L, "k-replay", "p");
        CommandView replayed = submit(1L, "k-replay", "p");

        assertThat(replayed.commandUuid()).isEqualTo(first.commandUuid());
        assertThat(replayed.acceptOrder()).isEqualTo(first.acceptOrder());
        assertThat(leaseService.getCurrent(device).lastAcceptedSeq()).isEqualTo(1L);
        assertThat(commandService.timeline(device)).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentContentConflicts() {
        submit(1L, "k-dup", "payload-a");

        assertThatThrownBy(() -> submit(2L, "k-dup", "payload-b"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("idempotency_conflict");
        assertThat(commandService.timeline(device)).hasSize(1);
    }

    @Test
    void staleClientSeqRejected() {
        submit(5L, "k5", "p");

        assertThatThrownBy(() -> submit(5L, "k6", "p"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("stale_client_seq");
        assertThatThrownBy(() -> submit(4L, "k7", "p"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("stale_client_seq");
    }

    @Test
    void wrongFenceTokenRejected() {
        assertThatThrownBy(() -> commandService.submit(
                device, lease.leaseId(), lease.fenceToken() + 1, 1L, "k", "p"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("fence_token_mismatch");
    }

    @Test
    void expiredLeaseRejected() {
        clock.advance(Duration.ofMinutes(61));

        assertThatThrownBy(() -> commandService.submit(
                device, lease.leaseId(), lease.fenceToken(), 1L, "k", "p"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("lease_expired");
    }

    @Test
    void timelineIsContiguousAcrossLeases() {
        submit(1L, "k1", "a");
        submit(2L, "k2", "b");

        clock.advance(Duration.ofMinutes(61));
        LeaseView next = leaseService.acquire(device, "client-b",
                clock.instant().plus(Duration.ofHours(1)));
        commandService.submit(device, next.leaseId(), next.fenceToken(), 1L, "k3", "c");

        List<CommandView> timeline = commandService.timeline(device);
        assertThat(timeline).hasSize(3);
        assertThat(timeline.stream().map(CommandView::acceptOrder)).containsExactly(1L, 2L, 3L);
        assertThat(timeline.stream().map(CommandView::fenceToken)).containsExactly(1L, 1L, 2L);
    }
}
