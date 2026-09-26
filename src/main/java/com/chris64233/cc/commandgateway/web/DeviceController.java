package com.chris64233.cc.commandgateway.web;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.chris64233.cc.commandgateway.service.CommandLifecycleService;
import com.chris64233.cc.commandgateway.service.CommandService;
import com.chris64233.cc.commandgateway.service.DeviceService;
import com.chris64233.cc.commandgateway.service.LeaseService;
import com.chris64233.cc.commandgateway.service.ReceiptService;
import com.chris64233.cc.commandgateway.web.dto.AcquireLeaseRequest;
import com.chris64233.cc.commandgateway.web.dto.CancelCommandRequest;
import com.chris64233.cc.commandgateway.web.dto.CommandView;
import com.chris64233.cc.commandgateway.web.dto.DeviceView;
import com.chris64233.cc.commandgateway.web.dto.LeaseView;
import com.chris64233.cc.commandgateway.web.dto.RegisterDeviceRequest;
import com.chris64233.cc.commandgateway.web.dto.ReportReceiptRequest;
import com.chris64233.cc.commandgateway.web.dto.ReceiptView;
import com.chris64233.cc.commandgateway.web.dto.SubmitCommandRequest;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final LeaseService leaseService;
    private final CommandService commandService;
    private final CommandLifecycleService commandLifecycleService;
    private final ReceiptService receiptService;

    public DeviceController(DeviceService deviceService,
                            LeaseService leaseService,
                            CommandService commandService,
                            CommandLifecycleService commandLifecycleService,
                            ReceiptService receiptService) {
        this.deviceService = deviceService;
        this.leaseService = leaseService;
        this.commandService = commandService;
        this.commandLifecycleService = commandLifecycleService;
        this.receiptService = receiptService;
    }

    @PostMapping
    public ResponseEntity<DeviceView> register(@Valid @RequestBody RegisterDeviceRequest request,
                                               UriComponentsBuilder uriBuilder) {
        DeviceView view = deviceService.register(request.deviceId());
        URI location = uriBuilder.path("/api/devices/{deviceId}").build(view.deviceId());
        return ResponseEntity.created(location).body(view);
    }

    @GetMapping("/{deviceId}")
    public DeviceView getDevice(@PathVariable String deviceId) {
        return deviceService.get(deviceId);
    }

    @PostMapping("/{deviceId}/leases")
    public ResponseEntity<LeaseView> acquireLease(@PathVariable String deviceId,
                                                  @Valid @RequestBody AcquireLeaseRequest request) {
        LeaseView view = leaseService.acquire(deviceId, request.clientId(), request.expiresAt());
        return ResponseEntity.status(201).body(view);
    }

    @GetMapping("/{deviceId}/leases/current")
    public LeaseView currentLease(@PathVariable String deviceId) {
        return leaseService.getCurrent(deviceId);
    }

    @GetMapping("/{deviceId}/status")
    public CommandService.DeviceStatus status(@PathVariable String deviceId) {
        return commandService.status(deviceId);
    }

    @PostMapping("/{deviceId}/commands")
    public ResponseEntity<CommandView> submitCommand(@PathVariable String deviceId,
                                                     @Valid @RequestBody SubmitCommandRequest request) {
        CommandView view = commandService.submit(
                deviceId,
                request.leaseId(),
                request.fenceToken(),
                request.clientSeq(),
                request.idempotencyKey(),
                request.payload(),
                request.deadlineAt());
        return ResponseEntity.status(201).body(view);
    }

    @PostMapping("/{deviceId}/commands/{commandUuid}/dispatch")
    public CommandView dispatchCommand(@PathVariable String deviceId,
                                       @PathVariable String commandUuid) {
        return commandLifecycleService.dispatch(deviceId, commandUuid);
    }

    @PostMapping("/{deviceId}/commands/{commandUuid}/cancel")
    public CommandView cancelCommand(@PathVariable String deviceId,
                                     @PathVariable String commandUuid,
                                     @Valid @RequestBody CancelCommandRequest request) {
        return commandLifecycleService.cancel(
                deviceId,
                commandUuid,
                request.leaseId(),
                request.fenceToken(),
                request.cancelId(),
                request.reason());
    }

    @PostMapping("/{deviceId}/timeout-scan")
    public List<CommandView> scanTimeouts(@PathVariable String deviceId) {
        return commandLifecycleService.scanTimeouts(deviceId);
    }

    @GetMapping("/{deviceId}/commands")
    public List<CommandView> timeline(@PathVariable String deviceId) {
        return commandService.timeline(deviceId);
    }

    @PostMapping("/{deviceId}/receipts")
    public ReceiptView reportReceipt(@PathVariable String deviceId,
                                     @Valid @RequestBody ReportReceiptRequest request) {
        return receiptService.report(
                deviceId,
                request.commandUuid(),
                request.leaseId(),
                request.fenceToken(),
                request.eventId(),
                request.kind(),
                request.content());
    }
}
