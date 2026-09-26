package com.chris64233.cc.commandgateway.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommandRecordRepository extends JpaRepository<CommandRecord, Long> {

    Optional<CommandRecord> findByLeaseIdAndIdempotencyKey(Long leaseId, String idempotencyKey);

    List<CommandRecord> findByDeviceIdOrderBySequenceAsc(Long deviceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommandRecord c where c.id = :id")
    Optional<CommandRecord> findByIdForUpdate(@Param("id") Long id);
}
