package com.chris64233.cc.commandgateway.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.commandgateway.domain.Device;
import com.chris64233.cc.commandgateway.repo.DeviceRepository;
import com.chris64233.cc.commandgateway.web.ApiException;
import com.chris64233.cc.commandgateway.web.dto.DeviceView;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final Clock clock;

    public DeviceService(DeviceRepository deviceRepository, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.clock = clock;
    }

    @Transactional
    public DeviceView register(String deviceId) {
        if (deviceRepository.existsById(deviceId)) {
            throw ApiException.conflict("device_exists", "device already registered: " + deviceId);
        }
        Device device = new Device(deviceId, Instant.now(clock));
        deviceRepository.save(device);
        return new DeviceView(device.getDeviceId(), device.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public DeviceView get(String deviceId) {
        Device device = requireDevice(deviceId);
        return new DeviceView(device.getDeviceId(), device.getCreatedAt());
    }

    private Device requireDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("device not found: " + deviceId));
    }
}
