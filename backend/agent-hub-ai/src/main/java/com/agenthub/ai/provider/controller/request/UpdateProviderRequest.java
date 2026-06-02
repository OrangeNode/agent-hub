package com.agenthub.ai.provider.controller.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateProviderRequest(
        @NotBlank(message = "供应商名称不能为空") String name,
        @NotBlank(message = "供应商类型不能为空") String providerType,
        @NotBlank(message = "协议类型不能为空") String protocolType,
        @NotBlank(message = "基础地址不能为空") String baseUrl,
        String apiKey,
        @NotBlank(message = "认证类型不能为空") String authType,
        String apiVersion,
        String extraHeaders,
        String extraBody,
        Boolean enabled,
        Integer sort
) {
}
