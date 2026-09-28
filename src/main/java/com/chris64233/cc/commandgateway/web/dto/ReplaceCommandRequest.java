package com.chris64233.cc.commandgateway.web.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 替换指令请求。新指令在当前有效租约上下文下接受，继承旧指令的设备上下文；
 * {@code deadlineAt} 缺省时继承旧指令的截止时间。
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
        String idempotencyKey,

        @NotNull
        String payload,

        Instant deadlineAt,

        /** 外部请求号，保证替换请求幂等；同号不同内容返回冲突。 */
        @NotBlank
        @Size(max = 128)
        String replaceId,

        @Size(max = 512)
        String reason) {
}
