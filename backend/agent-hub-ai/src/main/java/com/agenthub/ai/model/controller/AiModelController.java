package com.agenthub.ai.model.controller;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.command.UpdateModelCommand;
import com.agenthub.ai.model.controller.request.CreateModelRequest;
import com.agenthub.ai.model.controller.request.UpdateModelRequest;
import com.agenthub.ai.model.dto.AiModelItem;
import com.agenthub.ai.model.service.AiModelService;
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
@RequestMapping("/api/ai/models")
public class AiModelController {

    private final AiModelService modelService;

    public AiModelController(AiModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping
    public ApiResponse<List<AiModelItem>> listModels() {
        return ApiResponse.success(modelService.list());
    }

    @PostMapping
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
                request.sort()
        )));
    }

    @PutMapping("/{modelId}")
    public ApiResponse<AiModelItem> updateModel(
            @PathVariable Long modelId,
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
                request.sort()
        )));
    }

    @DeleteMapping("/{modelId}")
    public ApiResponse<Void> deleteModel(@PathVariable Long modelId) {
        modelService.delete(modelId);
        return ApiResponse.success();
    }

    @GetMapping("/available")
    public ApiResponse<List<AiModelItem>> listAvailableModels() {
        return ApiResponse.success(modelService.listAvailable());
    }
}
