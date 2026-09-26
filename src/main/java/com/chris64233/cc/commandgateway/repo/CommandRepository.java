package com.chris64233.cc.commandgateway.repo;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.JpaRepository;

import com.chris64233.cc.commandgateway.domain.CommandRecord;

public interface CommandRepository extends JpaRepository<CommandRecord, Long> {

    String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";

    Optional<CommandRecord> findByDeviceIdAndIdempotencyKey(String deviceId, String idempotencyKey);

    List<CommandRecord> findByDeviceIdOrderByAcceptOrderAsc(String deviceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = LOCK_TIMEOUT, value = "5000"))
    @Query("select c from CommandRecord c where c.commandUuid = :commandUuid")
    Optional<CommandRecord> findWithLockingByCommandUuid(String commandUuid);
}
