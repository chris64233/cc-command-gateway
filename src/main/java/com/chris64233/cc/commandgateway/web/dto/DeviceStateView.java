package com.chris64233.cc.commandgateway.web.dto;

public record DeviceStateView(String deviceNumber, long fencingToken, LeaseView currentLease) {
}
