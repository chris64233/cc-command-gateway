package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

public record ErrorView(String code, String message, Instant timestamp) {
}
