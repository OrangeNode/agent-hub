package com.agenthub.ai.model.service.impl;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.command.UpdateModelCommand;
import com.agenthub.ai.model.dto.AiModelItem;
import com.agenthub.ai.model.entity.AiModel;
import com.agenthub.ai.model.mapper.AiModelMapper;
import com.agenthub.ai.model.service.AiModelService;
import com.agenthub.ai.provider.service.AiProviderService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.Serializable;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiModelServiceImpl implements AiModelService {

    private final AiModelMapper modelMapper;
    private final AiProviderService providerService;

    public AiModelServiceImpl(AiModelMapper modelMapper, AiProviderService providerService) {
        this.modelMapper = modelMapper;
        this.providerService = providerService;
    }

    @Override
    public List<AiModelItem> list() {
        return modelMapper.selectJoinedItems();
    }

    @Override
    public List<AiModelItem> listAvailable() {
        return modelMapper.selectAvailableItems();
    }

    @Override
    @Transactional
    public AiModelItem create(CreateModelCommand command) {
        providerService.requireById(command.providerId());
        rejectDuplicateCode(command.providerId(), command.modelCode(), null);

        AiModel model = new AiModel();
        fillModel(model, command);
        modelMapper.insert(model);
        if (Boolean.TRUE.equals(model.getDefaultModel())) {
            modelMapper.clearDefaultModelExcept(model.getId());
        }
        return modelMapper.selectJoinedById(model.getId());
    }

    @Override
    @Transactional
    public AiModelItem update(Long modelId, UpdateModelCommand command) {
        AiModel existed = requireModel(modelId);
        providerService.requireById(command.providerId());
        rejectDuplicateCode(command.providerId(), command.modelCode(), existed.getId());

        AiModel updated = new AiModel();
        updated.setId(existed.getId());
        fillModel(updated, command);
        modelMapper.updateById(updated);
        if (Boolean.TRUE.equals(updated.getDefaultModel())) {
            modelMapper.clearDefaultModelExcept(updated.getId());
        }
        return modelMapper.selectJoinedById(modelId);
    }

    @Override
    @Transactional
    public void delete(Long modelId) {
        requireModel(modelId);
        modelMapper.deleteById((Serializable) modelId);
    }

    private AiModel requireModel(Long modelId) {
        AiModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模型不存在");
        }
        return model;
    }

    private void rejectDuplicateCode(Long providerId, String modelCode, Long excludedModelId) {
        LambdaQueryWrapper<AiModel> wrapper = new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getModelCode, modelCode);
        if (excludedModelId != null) {
            wrapper.ne(AiModel::getId, excludedModelId);
        }
        boolean existed = modelMapper.selectCount(wrapper) > 0;
        if (existed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型编码已存在");
        }
    }

    private void fillModel(AiModel model, CreateModelCommand command) {
        model.setProviderId(command.providerId());
        model.setModelCode(command.modelCode());
        model.setDisplayName(command.displayName());
        model.setCapabilityType(command.capabilityType());
        model.setStreamSupported(defaultStreamSupported(command.streamSupported()));
        model.setReasoningSupported(defaultReasoningSupported(command.reasoningSupported()));
        model.setContextWindow(command.contextWindow());
        model.setMaxOutputTokens(command.maxOutputTokens());
        model.setDefaultModel(defaultDefaultModel(command.defaultModel()));
        model.setEnabled(defaultEnabled(command.enabled()));
        model.setSort(defaultSort(command.sort()));
    }

    private void fillModel(AiModel model, UpdateModelCommand command) {
        model.setProviderId(command.providerId());
        model.setModelCode(command.modelCode());
        model.setDisplayName(command.displayName());
        model.setCapabilityType(command.capabilityType());
        model.setStreamSupported(defaultStreamSupported(command.streamSupported()));
        model.setReasoningSupported(defaultReasoningSupported(command.reasoningSupported()));
        model.setContextWindow(command.contextWindow());
        model.setMaxOutputTokens(command.maxOutputTokens());
        model.setDefaultModel(defaultDefaultModel(command.defaultModel()));
        model.setEnabled(defaultEnabled(command.enabled()));
        model.setSort(defaultSort(command.sort()));
    }

    private boolean defaultStreamSupported(Boolean streamSupported) {
        return streamSupported == null || streamSupported;
    }

    private boolean defaultReasoningSupported(Boolean reasoningSupported) {
        return reasoningSupported != null && reasoningSupported;
    }

    private boolean defaultDefaultModel(Boolean defaultModel) {
        return defaultModel != null && defaultModel;
    }

    private boolean defaultEnabled(Boolean enabled) {
        return enabled == null || enabled;
    }

    private int defaultSort(Integer sort) {
        return sort == null ? 0 : sort;
    }
}
