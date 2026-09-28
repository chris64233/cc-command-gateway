package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

/**
 * 替换关系视图。挂在被替换的旧命令上（其 {@code commandUuid} 为旧命令），
 * 同时通过 {@code replacementCommandUuid} 指向取代它的新命令；新命令上挂同一关系，
 * {@code replacementCommandUuid} 指向新命令自身，可据 role/状态区分新、旧命令。
 */
public record ReplaceView(
        String replaceId,
        String oldCommandUuid,
        String replacementCommandUuid,
        long fenceToken,
        String reason,
        Instant replacedAt) {
}
