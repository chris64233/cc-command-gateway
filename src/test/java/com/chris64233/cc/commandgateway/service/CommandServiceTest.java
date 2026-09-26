package com.chris64233.cc.commandgateway.service;

import com.chris64233.cc.commandgateway.web.dto.AcquireLeaseRequest;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.DeviceStateView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.SubmitCommandRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CommandServiceTest {

    @Autowired
    private DeviceGatewayService service;

    @Test
    void acceptsCommandWhenTokenLeaseAndSequenceAreValid() {
        String deviceNumber = "dev-cmd-happy";
        service.registerDevice(deviceNumber);
        LeaseView lease = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 60_000L));

        CommandView command = service.submitCommand(deviceNumber,
                new SubmitCommandRequest(lease.id(), lease.fencingToken(), 1, "k1", "open"));

        assertThat(command.id()).isPositive();
        assertThat(command.sequence()).isEqualTo(1);
        assertThat(command.status().name()).isEqualTo("ACCEPTED");
        assertThat(service.getDeviceState(deviceNumber).currentLease().lastAcceptedSequence())
                .isEqualTo(1);
    }

    @Test
    void rejectsStaleFencingToken() {
        String deviceNumber = "dev-cmd-token";
        service.registerDevice(deviceNumber);
        LeaseView lease = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 60_000L));

        assertThatThrownBy(() -> service.submitCommand(deviceNumber,
                new SubmitCommandRequest(lease.id(), lease.fencingToken() + 1, 1, "k1", "p")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("FENCING_TOKEN_MISMATCH"));
    }

    @Test
    void rejectsCommandsAfterLeaseExpiry() throws InterruptedException {
        String deviceNumber = "dev-cmd-expired";
        service.registerDevice(deviceNumber);
        LeaseView lease = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 200L));
        Thread.sleep(400);

        assertThatThrownBy(() -> service.submitCommand(deviceNumber,
                new SubmitCommandRequest(lease.id(), lease.fencingToken(), 1, "k1", "p")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("LEASE_EXPIRED"));
    }

    @Test
    void rejectsNonMonotonicSequence() {
        String deviceNumber = "dev-cmd-sequence";
        service.registerDevice(deviceNumber);
        LeaseView lease = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 60_000L));

        service.submitCommand(deviceNumber,
                new SubmitCommandRequest(lease.id(), lease.fencingToken(), 5, "k5", "p5"));

        assertThatThrownBy(() -> service.submitCommand(deviceNumber,
                new SubmitCommandRequest(lease.id(), lease.fencingToken(), 5, "k5b", "p5b")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("STALE_SEQUENCE"));
        assertThatThrownBy(() -> service.submitCommand(deviceNumber,
                new SubmitCommandRequest(lease.id(), lease.fencingToken(), 3, "k3", "p3")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("STALE_SEQUENCE"));
        assertThat(service.getTimeline(deviceNumber)).hasSize(1);
    }

    @Test
    void idempotentReplayReturnsOriginalRecordWithoutAdvancingSequence() {
        String deviceNumber = "dev-cmd-idempotent";
        service.registerDevice(deviceNumber);
        LeaseView lease = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 60_000L));

        SubmitCommandRequest first = new SubmitCommandRequest(
                lease.id(), lease.fencingToken(), 7, "same-key", "payload");
        CommandView original = service.submitCommand(deviceNumber, first);
        CommandView replay = service.submitCommand(deviceNumber, first);

        assertThat(replay.id()).isEqualTo(original.id());
        DeviceStateView state = service.getDeviceState(deviceNumber);
        assertThat(state.currentLease().lastAcceptedSequence()).isEqualTo(7);
        assertThat(service.getTimeline(deviceNumber)).hasSize(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsConflict() {
        String deviceNumber = "dev-cmd-key-conflict";
        service.registerDevice(deviceNumber);
        LeaseView lease = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 60_000L));

        service.submitCommand(deviceNumber,
                new SubmitCommandRequest(lease.id(), lease.fencingToken(), 1, "dup-key", "payload-a"));

        assertThatThrownBy(() -> service.submitCommand(deviceNumber,
                new SubmitCommandRequest(lease.id(), lease.fencingToken(), 2, "dup-key", "payload-b")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(service.getTimeline(deviceNumber)).hasSize(1);
    }

    @Test
    void concurrentSubmissionsKeepAcceptedCommandsLastSequenceAndPersistenceConsistent() throws Exception {
        String deviceNumber = "dev-cmd-race";
        service.registerDevice(deviceNumber);
        LeaseView lease = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 60_000L));

        int total = 24;
        List<Integer> sequences = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            sequences.add(i);
        }
        Collections.shuffle(sequences, new Random(42));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int sequence : sequences) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return service.submitCommand(deviceNumber, new SubmitCommandRequest(
                            lease.id(), lease.fencingToken(), sequence,
                            "key-" + sequence, "payload-" + sequence));
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }
        start.countDown();

        List<CommandView> accepted = new ArrayList<>();
        for (Future<Object> future : futures) {
            Object result = future.get(10, TimeUnit.SECONDS);
            if (result instanceof CommandView command) {
                accepted.add(command);
            } else {
                ApiException ex = (ApiException) result;
                assertThat(ex.getCode()).isEqualTo("STALE_SEQUENCE");
            }
        }
        pool.shutdown();

        List<CommandView> timeline = service.getTimeline(deviceNumber);
        List<Long> acceptedIds = accepted.stream().map(CommandView::id).toList();
        assertThat(timeline).extracting(CommandView::id)
                .containsExactlyInAnyOrderElementsOf(acceptedIds);
        assertThat(timeline).extracting(CommandView::id).doesNotHaveDuplicates();

        long expectedLastSequence = accepted.stream()
                .mapToLong(CommandView::sequence)
                .max().orElse(0);
        assertThat(service.getDeviceState(deviceNumber).currentLease().lastAcceptedSequence())
                .isEqualTo(expectedLastSequence);
    }

    @Test
    void concurrentReplaysOfSameKeyShareSinglePersistedCommand() throws Exception {
        String deviceNumber = "dev-cmd-replay-race";
        service.registerDevice(deviceNumber);
        LeaseView lease = service.acquireLease(deviceNumber,
                new AcquireLeaseRequest("client-a", 60_000L));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.submitCommand(deviceNumber, new SubmitCommandRequest(
                        lease.id(), lease.fencingToken(), 1, "once-key", "payload"));
            }));
        }
        start.countDown();

        long firstId = ((CommandView) futures.get(0).get(10, TimeUnit.SECONDS)).id();
        long secondId = ((CommandView) futures.get(1).get(10, TimeUnit.SECONDS)).id();
        pool.shutdown();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(service.getTimeline(deviceNumber)).hasSize(1);
        assertThat(service.getDeviceState(deviceNumber).currentLease().lastAcceptedSequence())
                .isEqualTo(1);
    }
}
