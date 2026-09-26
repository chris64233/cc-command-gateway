package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.commandgateway.domain.CommandState;
import com.chris64233.cc.commandgateway.repo.CommandRepository;
import com.chris64233.cc.commandgateway.repo.DeviceRepository;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.CancelView;

/**
 * 超时扫描：把已过截止时间且尚未进入设备执行阶段的指令取消。
 * 扫描使用统一时钟，可安全重试，不重复产生取消事件。
 */
@Service
public class TimeoutService {

    private static final List<CommandState> CANCELLABLE_STATES =
            List.of(CommandState.PENDING, CommandState.DISPATCHED);

    private final DeviceRepository deviceRepository;
    private final CommandRepository commandRepository;
    private final CancelService cancelService;
    private final Clock clock;

    public TimeoutService(DeviceRepository deviceRepository,
                          CommandRepository commandRepository,
                          CancelService cancelService,
                          Clock clock) {
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
        this.cancelService = cancelService;
        this.clock = clock;
    }

    /**
     * 扫描一台设备上过期的指令并逐条取消，返回本次扫描实际产生的取消记录。
     * 候选只取指令编号，逐条加锁后按与主动取消相同的规则判定终态竞争。
     */
    @Transactional
    public List<CancelView> scanDevice(String deviceId) {
        deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));

        Instant now = Instant.now(clock);
        List<String> candidates = commandRepository.findExpiredCommandUuids(
                deviceId, CANCELLABLE_STATES, now);

        List<CancelView> cancelled = new ArrayList<>(candidates.size());
        for (String commandUuid : candidates) {
            CancelView cancel = cancelService.cancelTimedOut(commandUuid);
            if (cancel != null) {
                cancelled.add(cancel);
            }
        }
        return cancelled;
    }
}
