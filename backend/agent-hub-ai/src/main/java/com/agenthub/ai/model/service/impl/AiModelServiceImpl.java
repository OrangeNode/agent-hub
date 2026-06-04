package com.agenthub.ai.model.service.impl;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.command.UpdateModelCommand;
import com.agenthub.ai.model.dto.AiModelItem;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.ai.model.entity.AiModel;
import com.agenthub.ai.model.mapper.AiModelMapper;
import com.agenthub.ai.model.service.AiModelService;
import com.agenthub.ai.provider.dto.AiProviderItem;
import com.agenthub.ai.provider.service.AiProviderService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.Serializable;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
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

    /**
     * 查询模型管理列表，包含供应商名称等展示字段。
     */
    @Override
    public List<AiModelItem> list() {
        return modelMapper.selectJoinedItems();
    }

    /**
     * 查询启用且供应商也启用的模型列表。
     */
    @Override
    public List<AiModelItem> listAvailable() {
        return modelMapper.selectAvailableItems();
    }

    /**
     * 获取指定模型运行时配置，并校验调用链路可用。
     */
    @Override
    public AiModelRuntimeConfig requireAvailableRuntimeConfig(Long modelId) {
        AiModelRuntimeConfig config = modelMapper.selectRuntimeConfigById(modelId);
        return requireUsableRuntimeConfig(config, "模型不存在");
    }

    /**
     * 获取默认模型运行时配置，并校验调用链路可用。
     */
    @Override
    public AiModelRuntimeConfig requireDefaultRuntimeConfig() {
        AiModelRuntimeConfig config = modelMapper.selectDefaultRuntimeConfig();
        return requireUsableRuntimeConfig(config, "默认模型不存在");
    }

    /**
     * 统一校验模型和供应商启用状态，避免聊天入口重复判断。
     */
    private AiModelRuntimeConfig requireUsableRuntimeConfig(AiModelRuntimeConfig config, String notFoundMessage) {
        if (config == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, notFoundMessage);
        }
        if (!config.enabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型不可用");
        }
        if (!config.providerEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "供应商不可用");
        }
        return config;
    }

    /**
     * 创建模型配置，必要时把其他模型取消默认。
     */
    @Override
    @Transactional
    public AiModelItem create(CreateModelCommand command) {
        AiProviderItem provider = providerService.requireById(command.providerId());
        rejectDuplicateCode(command.providerId(), command.modelCode(), null);
        rejectUnavailableDefaultModel(command.defaultModel(), command.enabled(), provider);

        AiModel model = new AiModel();
        fillModel(model, command);
        insertModel(model);
        if (Boolean.TRUE.equals(model.getDefaultModel())) {
            // 系统只允许一个默认模型，创建成功后再清理其他默认标记。
            modelMapper.clearDefaultModelExcept(model.getId());
        }
        return modelMapper.selectJoinedById(model.getId());
    }

    /**
     * 更新模型配置，必要时把其他模型取消默认。
     */
    @Override
    @Transactional
    public AiModelItem update(Long modelId, UpdateModelCommand command) {
        AiModel existed = requireModel(modelId);
        AiProviderItem provider = providerService.requireById(command.providerId());
        rejectDuplicateCode(command.providerId(), command.modelCode(), existed.getId());
        rejectUnavailableDefaultModel(command.defaultModel(), command.enabled(), provider);

        AiModel updated = new AiModel();
        updated.setId(existed.getId());
        fillModel(updated, command);
        updateModel(updated);
        if (Boolean.TRUE.equals(updated.getDefaultModel())) {
            // 保持默认模型互斥，当前模型以外的默认标记全部清除。
            modelMapper.clearDefaultModelExcept(updated.getId());
        }
        return modelMapper.selectJoinedById(modelId);
    }

    /**
     * 删除模型配置。
     */
    @Override
    @Transactional
    public void delete(Long modelId) {
        requireModel(modelId);
        modelMapper.deleteById((Serializable) modelId);
    }

    /**
     * 查询模型实体，不存在时统一抛出业务异常。
     */
    private AiModel requireModel(Long modelId) {
        AiModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模型不存在");
        }
        return model;
    }

    /**
     * 插入模型时将数据库唯一约束冲突转换成业务异常。
     */
    private void insertModel(AiModel model) {
        try {
            modelMapper.insert(model);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型编码已存在");
        }
    }

    /**
     * 更新模型时将数据库唯一约束冲突转换成业务异常。
     */
    private void updateModel(AiModel model) {
        try {
            modelMapper.updateById(model);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型编码已存在");
        }
    }

    /**
     * 校验同一供应商下模型编码唯一，更新时排除当前模型。
     */
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

    /**
     * 默认模型必须自身启用且供应商启用，否则聊天入口无法回退到可用模型。
     */
    private void rejectUnavailableDefaultModel(Boolean defaultModel, Boolean enabled, AiProviderItem provider) {
        if (!Boolean.TRUE.equals(defaultModel)) {
            return;
        }
        if (!defaultEnabled(enabled)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "默认模型必须处于可用状态");
        }
        if (!provider.enabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "供应商不可用");
        }
    }

    private void fillModel(AiModel model, CreateModelCommand command) {
        model.setProviderId(command.providerId());
        model.setModelCode(command.modelCode());
        model.setDisplayName(command.displayName());
        model.setCapabilityType(command.capabilityType());
        model.setStreamSupported(defaultStreamSupported(command.streamSupported()));
        model.setReasoningSupported(defaultReasoningSupported(command.reasoningSupported()));
        model.setImageInputSupported(command.imageInputSupported());
        model.setImageTokenEstimate(command.imageTokenEstimate());
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
        model.setImageInputSupported(command.imageInputSupported());
        model.setImageTokenEstimate(command.imageTokenEstimate());
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
