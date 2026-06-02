package com.agenthub.ai.provider.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProviderRequest(
        @NotBlank(message = "供应商编码不能为空")
        @Size(max = 64, message = "供应商编码长度不能超过64个字符")
        String code,
        @NotBlank(message = "供应商名称不能为空")
        @Size(max = 128, message = "供应商名称长度不能超过128个字符")
        String name,
        @NotBlank(message = "供应商类型不能为空")
        @Size(max = 32, message = "供应商类型长度不能超过32个字符")
        String providerType,
        @NotBlank(message = "协议类型不能为空")
        @Size(max = 64, message = "协议类型长度不能超过64个字符")
        String protocolType,
        @NotBlank(message = "基础地址不能为空")
        @Size(max = 512, message = "基础地址长度不能超过512个字符")
        String baseUrl,
        @Size(max = 4096, message = "API Key 长度不能超过4096个字符")
        String apiKey,
        @NotBlank(message = "认证类型不能为空")
        @Size(max = 64, message = "认证类型长度不能超过64个字符")
        String authType,
        @Size(max = 64, message = "API 版本长度不能超过64个字符")
        String apiVersion,
        @Size(max = 4000, message = "扩展请求头长度不能超过4000个字符")
        String extraHeaders,
        @Size(max = 4000, message = "扩展请求体长度不能超过4000个字符")
        String extraBody,
        Boolean enabled,
        Integer sort
) {
}
