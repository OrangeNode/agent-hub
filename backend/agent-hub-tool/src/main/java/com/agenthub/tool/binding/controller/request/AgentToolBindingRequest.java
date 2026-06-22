package com.agenthub.tool.binding.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AgentToolBindingRequest(
        @NotBlank(message = "工具标识不能为空")
        @Size(max = 320, message = "工具标识不能超过 320 个字符")
        String toolName,

        @NotBlank(message = "工具来源不能为空")
        @Size(max = 32, message = "工具来源不能超过 32 个字符")
        String providerType,

        @NotNull(message = "工具来源引用 ID 不能为空")
        @Positive(message = "工具来源引用 ID 必须为正数")
        Long providerRefId,

        Integer sort
) {
}
