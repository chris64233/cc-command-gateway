package com.chris64233.cc.commandgateway.repo;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.chris64233.cc.commandgateway.domain.Device;

public interface DeviceRepository extends JpaRepository<Device, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select d from Device d where d.deviceId = :deviceId")
    Optional<Device> findByIdForUpdate(@Param("deviceId") String deviceId);
}
