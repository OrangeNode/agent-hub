package com.agenthub.ai.model.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateModelRequest(
        @NotNull(message = "供应商不能为空")
        @Positive(message = "供应商必须为正数")
        Long providerId,
        @NotBlank(message = "模型编码不能为空")
        @Size(max = 128, message = "模型编码长度不能超过128个字符")
        String modelCode,
        @NotBlank(message = "模型名称不能为空")
        @Size(max = 128, message = "模型名称长度不能超过128个字符")
        String displayName,
        @NotBlank(message = "能力类型不能为空")
        @Size(max = 64, message = "能力类型长度不能超过64个字符")
        String capabilityType,
        Boolean streamSupported,
        Boolean reasoningSupported,
        @Positive(message = "上下文窗口必须为正数")
        Integer contextWindow,
        @Positive(message = "最大输出 Token 数必须为正数")
        Integer maxOutputTokens,
        Boolean defaultModel,
        Boolean enabled,
        @Min(value = 0, message = "排序值不能小于0")
        Integer sort
) {
}
