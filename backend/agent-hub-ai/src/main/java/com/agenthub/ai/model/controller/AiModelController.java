package com.agenthub.ai.model.controller;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.command.UpdateModelCommand;
import com.agenthub.ai.model.controller.request.CreateModelRequest;
import com.agenthub.ai.model.controller.request.UpdateModelRequest;
import com.agenthub.ai.model.dto.AiModelItem;
import com.agenthub.ai.model.service.AiModelService;
import com.agenthub.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/ai/models")
public class AiModelController {

    private final AiModelService modelService;

    public AiModelController(AiModelService modelService) {
        this.modelService = modelService;
    }

    /**
     * 查询 AI 模型管理列表。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ai:model:list')")
    public ApiResponse<List<AiModelItem>> listModels() {
        return ApiResponse.success(modelService.list());
    }

    /**
     * 创建 AI 模型配置。
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ai:model:list')")
    public ApiResponse<AiModelItem> createModel(@Valid @RequestBody CreateModelRequest request) {
        return ApiResponse.success(modelService.create(new CreateModelCommand(
                request.providerId(),
                request.modelCode(),
                request.displayName(),
                request.capabilityType(),
                request.streamSupported(),
                request.reasoningSupported(),
                request.contextWindow(),
                request.maxOutputTokens(),
                request.defaultModel(),
                request.enabled(),
                request.sort(),
                Boolean.TRUE.equals(request.imageInputSupported()),
                request.imageTokenEstimate()
        )));
    }

    /**
     * 更新 AI 模型配置。
     */
    @PutMapping("/{modelId}")
    @PreAuthorize("hasAuthority('ai:model:list')")
    public ApiResponse<AiModelItem> updateModel(
            @PathVariable @Positive(message = "模型必须为正数") Long modelId,
            @Valid @RequestBody UpdateModelRequest request
    ) {
        return ApiResponse.success(modelService.update(modelId, new UpdateModelCommand(
                request.providerId(),
                request.modelCode(),
                request.displayName(),
                request.capabilityType(),
                request.streamSupported(),
                request.reasoningSupported(),
                request.contextWindow(),
                request.maxOutputTokens(),
                request.defaultModel(),
                request.enabled(),
                request.sort(),
                Boolean.TRUE.equals(request.imageInputSupported()),
                request.imageTokenEstimate()
        )));
    }

    /**
     * 删除 AI 模型配置。
     */
    @DeleteMapping("/{modelId}")
    @PreAuthorize("hasAuthority('ai:model:list')")
    public ApiResponse<Void> deleteModel(
            @PathVariable @Positive(message = "模型必须为正数") Long modelId
    ) {
        modelService.delete(modelId);
        return ApiResponse.success();
    }

    /**
     * 查询当前可用于聊天调用的 AI 模型列表。
     */
    @GetMapping("/available")
    public ApiResponse<List<AiModelItem>> listAvailableModels() {
        return ApiResponse.success(modelService.listAvailable());
    }
}
