package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

/**
 * 指令时间线中的替换关系。旧指令视图中 {@code newCommandUuid} 指向取代它的新指令；
 * 新指令视图中 {@code oldCommandUuid} 指向被它取代的旧指令。
 */
public record ReplacementView(
        String replaceId,
        String oldCommandUuid,
        String newCommandUuid,
        long fenceToken,
        long clientSeq,
        String idempotencyKey,
        String reason,
        Instant replacedAt) {
}
