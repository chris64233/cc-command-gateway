package com.chris64233.cc.commandgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

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
class LeaseAndFenceServiceTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private LeaseService leaseService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private MutableClock clock;

    @Test
    void singleActiveLeaseAndStrictlyIncreasingFenceToken() {
        String device = "dev-lease-" + UUID.randomUUID();
        deviceService.register(device);

        LeaseView first = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofHours(1)));
        assertThat(first.fenceToken()).isEqualTo(1L);
        assertThat(first.active()).isTrue();

        assertThatThrownBy(() -> leaseService.acquire(device, "client-b",
                clock.instant().plus(Duration.ofHours(1))))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("lease_active");

        clock.advance(Duration.ofMinutes(61));

        LeaseView second = leaseService.acquire(device, "client-b",
                clock.instant().plus(Duration.ofHours(1)));
        assertThat(second.fenceToken()).isEqualTo(2L);
        assertThat(second.leaseId()).isNotEqualTo(first.leaseId());

        CommandService.DeviceStatus status = commandService.status(device);
        assertThat(status.currentFenceToken()).isEqualTo(2L);
        assertThat(status.currentLease().leaseId()).isEqualTo(second.leaseId());
    }

    @Test
    void expiredOldLeaseCannotIssueCommandsAfterNewLease() {
        String device = "dev-lease-" + UUID.randomUUID();
        deviceService.register(device);
        LeaseView first = leaseService.acquire(device, "client-a",
                clock.instant().plus(Duration.ofMinutes(10)));

        clock.advance(Duration.ofMinutes(11));
        LeaseView second = leaseService.acquire(device, "client-b",
                clock.instant().plus(Duration.ofMinutes(10)));

        assertThatThrownBy(() -> commandService.submit(
                device, first.leaseId(), first.fenceToken(), 1L, "k-old", "p"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("stale_fence_token");

        CommandView command = commandService.submit(
                device, second.leaseId(), second.fenceToken(), 1L, "k-new", "p");
        assertThat(command.fenceToken()).isEqualTo(2L);
    }

    @Test
    void acquireLeaseOnUnknownDeviceRejected() {
        assertThatThrownBy(() -> leaseService.acquire("missing", "client-a",
                clock.instant().plus(Duration.ofMinutes(10))))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("not_found");
    }
}
