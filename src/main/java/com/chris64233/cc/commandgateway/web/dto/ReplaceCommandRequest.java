package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 替换请求：用一条新命令完整取代尚未执行的旧命令。
 * {@code replaceId} 为外部替换请求号，保证替换操作幂等；
 * 新命令沿用提交指令的字段（租约、令牌、客户端序号、内容、截止时间），
 * 其幂等键即采用 {@code replaceId}，保证一次替换对应一条可重放的新命令。
 */
public record ReplaceCommandRequest(
        @NotBlank
        @Size(max = 64)
        String leaseId,

        @Positive
        long fenceToken,

        @Positive
        long clientSeq,

        @NotBlank
        @Size(max = 128)
        String replaceId,

        @NotNull
        String payload,

        Instant deadlineAt,

        @Size(max = 512)
        String reason) {
}
