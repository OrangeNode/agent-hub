package com.agenthub.ai.model.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateModelRequest(
        @NotNull(message = "供应商不能为空")
        Long providerId,
        @NotBlank(message = "模型编码不能为空")
        @Size(max = 128, message = "模型编码长度不能超过128个字符")
        String modelCode,
        @NotBlank(message = "模型名称不能为空")
        @Size(max = 128, message = "模型名称长度不能超过128个字符")
        String displayName,
        @NotBlank(message = "能力类型不能为空")
        @Size(max = 32, message = "能力类型长度不能超过32个字符")
        String capabilityType,
        Boolean streamSupported,
        Boolean reasoningSupported,
        Integer contextWindow,
        Integer maxOutputTokens,
        Boolean defaultModel,
        Boolean enabled,
        Integer sort
) {
}
