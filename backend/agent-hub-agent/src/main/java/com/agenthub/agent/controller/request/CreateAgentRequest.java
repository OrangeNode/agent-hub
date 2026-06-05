package com.agenthub.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateAgentRequest(
        @NotBlank(message = "Agent 标识不能为空")
        @Size(max = 128, message = "Agent 标识不能超过 128 个字符")
        String agentKey,

        @NotBlank(message = "Agent 名称不能为空")
        @Size(max = 128, message = "Agent 名称不能超过 128 个字符")
        String name,

        @Size(max = 16, message = "Agent 简称不能超过 16 个字符")
        String shortName,

        @Size(max = 512, message = "Agent 描述不能超过 512 个字符")
        String description,

        @NotBlank(message = "系统提示词不能为空")
        @Size(max = 10000, message = "系统提示词不能超过 10000 个字符")
        String systemPrompt,

        @Positive(message = "默认模型必须为正数")
        Long defaultModelId,

        Boolean enabled,

        @PositiveOrZero(message = "排序不能为负数")
        Integer sort
) {
}
