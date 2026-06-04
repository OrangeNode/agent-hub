package com.agenthub.ai.provider.controller;

import com.agenthub.ai.provider.command.CreateProviderCommand;
import com.agenthub.ai.provider.command.UpdateProviderCommand;
import com.agenthub.ai.provider.controller.request.CreateProviderRequest;
import com.agenthub.ai.provider.controller.request.UpdateProviderRequest;
import com.agenthub.ai.provider.dto.AiProviderItem;
import com.agenthub.ai.provider.service.AiProviderService;
import com.agenthub.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/providers")
public class AiProviderController {

    private final AiProviderService providerService;

    public AiProviderController(AiProviderService providerService) {
        this.providerService = providerService;
    }

    /**
     * 查询 AI 供应商管理列表。
     */
    @GetMapping
    public ApiResponse<List<AiProviderItem>> listProviders() {
        return ApiResponse.success(providerService.list());
    }

    /**
     * 创建 AI 供应商配置。
     */
    @PostMapping
    public ApiResponse<AiProviderItem> createProvider(@Valid @RequestBody CreateProviderRequest request) {
        return ApiResponse.success(providerService.create(new CreateProviderCommand(
                request.code(),
                request.name(),
                request.providerType(),
                request.protocolType(),
                request.baseUrl(),
                request.apiKey(),
                request.authType(),
                request.apiVersion(),
                request.extraHeaders(),
                request.extraBody(),
                request.enabled(),
                request.sort()
        )));
    }

    /**
     * 更新 AI 供应商配置。
     */
    @PutMapping("/{providerId}")
    public ApiResponse<AiProviderItem> updateProvider(
            @PathVariable Long providerId,
            @Valid @RequestBody UpdateProviderRequest request
    ) {
        return ApiResponse.success(providerService.update(providerId, new UpdateProviderCommand(
                request.name(),
                request.providerType(),
                request.protocolType(),
                request.baseUrl(),
                request.apiKey(),
                request.authType(),
                request.apiVersion(),
                request.extraHeaders(),
                request.extraBody(),
                request.enabled(),
                request.sort()
        )));
    }

    /**
     * 删除 AI 供应商配置。
     */
    @DeleteMapping("/{providerId}")
    public ApiResponse<Void> deleteProvider(@PathVariable Long providerId) {
        providerService.delete(providerId);
        return ApiResponse.success();
    }
}
