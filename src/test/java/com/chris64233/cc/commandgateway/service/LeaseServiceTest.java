package com.chris64233.cc.commandgateway.service;

import com.chris64233.cc.commandgateway.web.dto.AcquireLeaseRequest;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.SubmitCommandRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LeaseServiceTest {

    @Autowired
    private DeviceGatewayService service;

    @Test
    void grantsExclusiveLeaseWithStrictlyIncreasingFencingToken() {
        String deviceNumber = "dev-lease-exclusive";
        service.registerDevice(deviceNumber);

        LeaseView first = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 60_000L));
        assertThat(first.fencingToken()).isEqualTo(1);
        assertThat(first.active()).isTrue();

        assertThatThrownBy(() -> service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-b", 60_000L)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("LEASE_ACTIVE"));

        assertThat(service.getDeviceState(deviceNumber).currentLease().id()).isEqualTo(first.id());
    }

    @Test
    void expiredLeaseIsReplacedByLargerTokenAndOldLeaseCanNoLongerSubmit() throws InterruptedException {
        String deviceNumber = "dev-lease-expiry";
        service.registerDevice(deviceNumber);

        LeaseView old = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-old", 200L));
        Thread.sleep(400);

        LeaseView current = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-new", 60_000L));
        assertThat(current.fencingToken()).isGreaterThan(old.fencingToken());
        assertThat(current.fencingToken()).isEqualTo(2);
        assertThat(service.getDeviceState(deviceNumber).fencingToken()).isEqualTo(2);

        assertThatThrownBy(() -> service.submitCommand(deviceNumber,
                new SubmitCommandRequest(old.id(), old.fencingToken(), 1, "k-old", "payload")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FENCING_TOKEN_MISMATCH"));

        service.submitCommand(deviceNumber,
                new SubmitCommandRequest(current.id(), current.fencingToken(), 1, "k-new", "payload"));
    }

    @Test
    void concurrentAcquisitionGrantsExactlyOneActiveLeaseWithSingleTokenIncrement() throws Exception {
        String deviceNumber = "dev-lease-race";
        service.registerDevice(deviceNumber);

        int competitors = 8;
        ExecutorService pool = Executors.newFixedThreadPool(competitors);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < competitors; i++) {
            final String clientId = "client-" + i;
            tasks.add(() -> {
                start.await();
                try {
                    return service.acquireLease(deviceNumber,
                            new AcquireLeaseRequest(clientId, 60_000L));
                } catch (ApiException ex) {
                    return ex;
                }
            });
        }
        start.countDown();
        List<Future<Object>> futures = tasks.stream().map(pool::submit).toList();

        int granted = 0;
        int rejected = 0;
        for (Future<Object> future : futures) {
            Object result = future.get(10, TimeUnit.SECONDS);
            if (result instanceof LeaseView) {
                granted++;
            } else if (result instanceof ApiException ex && "LEASE_ACTIVE".equals(ex.getCode())) {
                rejected++;
            }
        }
        pool.shutdown();

        assertThat(granted).isEqualTo(1);
        assertThat(rejected).isEqualTo(competitors - 1);
        assertThat(service.getDeviceState(deviceNumber).fencingToken()).isEqualTo(1);
    }
}
