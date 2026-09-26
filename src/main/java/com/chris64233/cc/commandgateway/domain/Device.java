package com.chris64233.cc.commandgateway.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "device")
public class Device {

    @Id
    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "current_lease_id", length = 64)
    private String currentLeaseId;

    @Column(name = "fence_token", nullable = false)
    private long fenceToken;

    @Column(name = "next_accept_order", nullable = false)
    private long nextAcceptOrder = 1L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Device() {
    }

    public Device(String deviceId, Instant createdAt) {
        this.deviceId = deviceId;
        this.createdAt = createdAt;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getCurrentLeaseId() {
        return currentLeaseId;
    }

    public void setCurrentLeaseId(String currentLeaseId) {
        this.currentLeaseId = currentLeaseId;
    }

    public long getFenceToken() {
        return fenceToken;
    }

    public void setFenceToken(long fenceToken) {
        this.fenceToken = fenceToken;
    }

    public long getNextAcceptOrder() {
        return nextAcceptOrder;
    }

    public void advanceAcceptOrder() {
        this.nextAcceptOrder++;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
