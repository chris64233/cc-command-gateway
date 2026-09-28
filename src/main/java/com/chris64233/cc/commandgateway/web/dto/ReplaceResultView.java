package com.chris64233.cc.commandgateway.web.dto;

/** 替换结果：替换关系与继承上下文后接受的新指令。 */
public record ReplaceResultView(
        ReplacementView replacement,
        CommandView newCommand) {
}
