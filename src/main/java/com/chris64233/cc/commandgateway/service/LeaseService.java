package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.commandgateway.domain.Device;
import com.chris64233.cc.commandgateway.domain.Lease;
import com.chris64233.cc.commandgateway.repo.DeviceRepository;
import com.chris64233.cc.commandgateway.repo.LeaseRepository;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;

@Service
public class LeaseService {

    private final DeviceRepository deviceRepository;
    private final LeaseRepository leaseRepository;
    private final Clock clock;

    public LeaseService(DeviceRepository deviceRepository, LeaseRepository leaseRepository, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.leaseRepository = leaseRepository;
        this.clock = clock;
    }

    @Transactional
    public LeaseView acquire(String deviceId, String clientId, Instant expiresAt) {
        Instant now = Instant.now(clock);
        if (!expiresAt.isAfter(now)) {
            throw ApiException.conflict("lease_expires_in_past", "expiresAt must be in the future");
        }

        Device device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));

        Lease current = currentLeaseOf(device);
        if (current != null) {
            throw ApiException.conflict("lease_active",
                    "an active lease already exists for device: " + deviceId);
        }

        long nextFenceToken = device.getFenceToken() + 1;
        String leaseId = UUID.randomUUID().toString();
        Lease lease = new Lease(leaseId, deviceId, clientId, nextFenceToken, now, expiresAt);
        leaseRepository.save(lease);

        device.setFenceToken(nextFenceToken);
        device.setCurrentLeaseId(leaseId);

        return toView(lease, now, true);
    }

    @Transactional(readOnly = true)
    public LeaseView getCurrent(String deviceId) {
        Instant now = Instant.now(clock);
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));
        Lease lease = currentLeaseOf(device);
        if (lease == null) {
            throw ApiException.notFound("no active lease for device: " + deviceId);
        }
        return toView(lease, now, true);
    }

    private Lease currentLeaseOf(Device device) {
        String currentLeaseId = device.getCurrentLeaseId();
        if (currentLeaseId == null) {
            return null;
        }
        Lease lease = leaseRepository.findByLeaseId(currentLeaseId).orElse(null);
        if (lease == null || lease.isExpiredAt(Instant.now(clock))) {
            return null;
        }
        return lease;
    }

    private LeaseView toView(Lease lease, Instant now, boolean active) {
        return new LeaseView(
                lease.getLeaseId(),
                lease.getDeviceId(),
                lease.getClientId(),
                lease.getFenceToken(),
                lease.getLastAcceptedSeq(),
                lease.getAcquiredAt(),
                lease.getExpiresAt(),
                active && !lease.isExpiredAt(now));
    }
}
