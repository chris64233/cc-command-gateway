package com.chris64233.cc.commandgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;

@SpringBootTest
@Import(TestClockConfig.class)
class CommandConcurrencyTest {

    private static final int THREADS = 8;
    private static final int COMMANDS_PER_THREAD = 25;

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

    @Test
    void concurrentSubmitsProduceContiguousOrderAndPersistAllCommands() throws Exception {
        String deviceId = "dev-concurrent-1";
        deviceService.register(deviceId);
        LeaseView lease = leaseService.acquire(deviceId, "client-a",
                clock.instant().plus(Duration.ofHours(1)));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicLong seqSource = new AtomicLong();

        for (int thread = 0; thread < THREADS; thread++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                for (int index = 0; index < COMMANDS_PER_THREAD; index++) {
                    while (true) {
                        long seq = seqSource.incrementAndGet();
                        try {
                            commandService.submit(deviceId, lease.leaseId(), lease.fenceToken(),
                                    seq, "key-" + seq, "payload-" + seq);
                            break;
                        } catch (com.chris64233.cc.commandgateway.web.ApiException ex) {
                            if (!"stale_client_seq".equals(ex.getCode())) {
                                throw ex;
                            }
                        }
                    }
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<CommandView> timeline = commandService.timeline(deviceId);
        int total = THREADS * COMMANDS_PER_THREAD;
        assertThat(timeline).hasSize(total);

        List<Long> acceptOrders = timeline.stream().map(CommandView::acceptOrder).sorted().toList();
        for (int index = 0; index < total; index++) {
            assertThat(acceptOrders.get(index)).isEqualTo(index + 1L);
        }

        assertThat(timeline.stream().map(CommandView::clientSeq).distinct().count()).isEqualTo(total);

        LeaseView current = leaseService.getCurrent(deviceId);
        assertThat(current.lastAcceptedSeq()).isEqualTo(seqSource.get());
    }

    @Test
    void concurrentReplayOfSameIdempotencyKeyRecordsExactlyOnce() throws Exception {
        String deviceId = "dev-concurrent-2";
        deviceService.register(deviceId);
        LeaseView lease = leaseService.acquire(deviceId, "client-a",
                clock.instant().plus(Duration.ofHours(1)));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int thread = 0; thread < THREADS; thread++) {
            futures.add(pool.submit(() -> {
                start.await();
                return commandService.submit(deviceId, lease.leaseId(), lease.fenceToken(),
                        1L, "shared-key", "shared-payload").commandUuid();
            }));
        }

        start.countDown();
        String firstUuid = null;
        for (Future<String> future : futures) {
            String uuid = future.get(30, TimeUnit.SECONDS);
            if (firstUuid == null) {
                firstUuid = uuid;
            } else {
                assertThat(uuid).isEqualTo(firstUuid);
            }
        }
        pool.shutdown();

        List<CommandView> timeline = commandService.timeline(deviceId);
        assertThat(timeline).hasSize(1);
        assertThat(leaseService.getCurrent(deviceId).lastAcceptedSeq()).isEqualTo(1L);
    }

    @Test
    void competingTerminalReceiptsLeaveCommandInExactlyOneTerminalState() throws Exception {
        String deviceId = "dev-concurrent-3";
        deviceService.register(deviceId);
        LeaseView lease = leaseService.acquire(deviceId, "client-a",
                clock.instant().plus(Duration.ofHours(1)));
        CommandView command = commandService.submit(deviceId, lease.leaseId(), lease.fenceToken(),
                1L, "cmd-terminal", "payload");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int index = 0; index < 2; index++) {
            final ReceiptKind kind = index == 0 ? ReceiptKind.SUCCEEDED : ReceiptKind.FAILED;
            final String eventId = "event-" + index;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    receiptService.report(deviceId, command.commandUuid(), lease.leaseId(),
                            lease.fenceToken(), eventId, kind, "result");
                    return true;
                } catch (RuntimeException ex) {
                    return false;
                }
            }));
        }

        start.countDown();
        int accepted = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                accepted++;
            }
        }
        pool.shutdown();

        assertThat(accepted).isEqualTo(1);
        CommandState state = commandService.timeline(deviceId).get(0).state();
        assertThat(state).isIn(CommandState.SUCCEEDED, CommandState.FAILED);
    }
}
