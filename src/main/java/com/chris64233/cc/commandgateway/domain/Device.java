package com.chris64233.cc.commandgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "devices")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_number", nullable = false, unique = true, length = 64)
    private String deviceNumber;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "current_lease_id")
    private Long currentLeaseId;

    protected Device() {
    }

    public Device(String deviceNumber) {
        this.deviceNumber = deviceNumber;
        this.fencingToken = 0;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceNumber() {
        return deviceNumber;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public void setFencingToken(long fencingToken) {
        this.fencingToken = fencingToken;
    }

    public Long getCurrentLeaseId() {
        return currentLeaseId;
    }

    public void setCurrentLeaseId(Long currentLeaseId) {
        this.currentLeaseId = currentLeaseId;
    }
}
