package com.chris64233.cc.commandgateway.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceNumber(String deviceNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.deviceNumber = :deviceNumber")
    Optional<Device> findByDeviceNumberForUpdate(@Param("deviceNumber") String deviceNumber);
}
