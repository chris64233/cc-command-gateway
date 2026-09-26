package com.chris64233.cc.commandgateway.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.cc.commandgateway.domain.CommandRecord;
import com.chris64233.cc.commandgateway.domain.CommandState;

public interface CommandRepository extends JpaRepository<CommandRecord, Long> {

    String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";

    Optional<CommandRecord> findByDeviceIdAndIdempotencyKey(String deviceId, String idempotencyKey);

    List<CommandRecord> findByDeviceIdOrderByAcceptOrderAsc(String deviceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = LOCK_TIMEOUT, value = "5000"))
    @Query("select c from CommandRecord c where c.commandUuid = :commandUuid")
    Optional<CommandRecord> findWithLockingByCommandUuid(String commandUuid);

    /**
     * 超时候选指令编号。只返回编号而不加载实体，避免扫描事务把未加锁的
     * 实体装入持久化上下文；真正的状态判定在逐条加锁后进行。
     */
    @Query("select c.commandUuid from CommandRecord c where c.deviceId = :deviceId "
            + "and c.state in :states and c.deadlineAt is not null and c.deadlineAt <= :now "
            + "order by c.acceptOrder asc")
    List<String> findExpiredCommandUuids(String deviceId, List<CommandState> states, Instant now);
}
