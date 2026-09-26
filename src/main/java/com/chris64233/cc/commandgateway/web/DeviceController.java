package com.chris64233.cc.commandgateway.web;

import com.chris64233.cc.commandgateway.service.DeviceGatewayService;
import com.chris64233.cc.commandgateway.web.dto.AcquireLeaseRequest;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.DeviceStateView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.ReceiptRequest;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;
import com.chris64233.cc.commandgateway.web.dto.RegisterDeviceRequest;
import com.chris64233.cc.commandgateway.web.dto.SubmitCommandRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceGatewayService service;

    public DeviceController(DeviceGatewayService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceStateView registerDevice(@Valid @RequestBody RegisterDeviceRequest request) {
        return service.registerDevice(request.deviceNumber());
    }

    @GetMapping("/{deviceNumber}")
    public DeviceStateView getDeviceState(@PathVariable String deviceNumber) {
        return service.getDeviceState(deviceNumber);
    }

    @PostMapping("/{deviceNumber}/leases")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaseView acquireLease(@PathVariable String deviceNumber,
                                  @Valid @RequestBody AcquireLeaseRequest request) {
        return service.acquireLease(deviceNumber, request);
    }

    @PostMapping("/{deviceNumber}/commands")
    public CommandView submitCommand(@PathVariable String deviceNumber,
                                     @Valid @RequestBody SubmitCommandRequest request) {
        return service.submitCommand(deviceNumber, request);
    }

    @PostMapping("/{deviceNumber}/commands/{commandId}/receipts")
    public ReceiptView recordReceipt(@PathVariable String deviceNumber,
                                     @PathVariable long commandId,
                                     @Valid @RequestBody ReceiptRequest request) {
        return service.recordReceipt(deviceNumber, commandId, request);
    }

    @GetMapping("/{deviceNumber}/commands")
    public List<CommandView> getTimeline(@PathVariable String deviceNumber) {
        return service.getTimeline(deviceNumber);
    }
}
