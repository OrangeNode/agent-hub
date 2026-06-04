package com.agenthub.ai.model.service;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.command.UpdateModelCommand;
import com.agenthub.ai.model.dto.AiModelItem;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import java.util.List;

public interface AiModelService {

    /**
     * 查询模型管理列表，包含供应商展示信息。
     */
    List<AiModelItem> list();

    /**
     * 查询当前可用于聊天调用的模型列表。
     */
    List<AiModelItem> listAvailable();

    /**
     * 获取指定模型的运行时配置，并校验模型和供应商均可用。
     */
    AiModelRuntimeConfig requireAvailableRuntimeConfig(Long modelId);

    /**
     * 获取默认模型的运行时配置，并校验模型和供应商均可用。
     */
    AiModelRuntimeConfig requireDefaultRuntimeConfig();

    /**
     * 创建模型配置并处理默认模型互斥关系。
     */
    AiModelItem create(CreateModelCommand command);

    /**
     * 更新模型配置并处理默认模型互斥关系。
     */
    AiModelItem update(Long modelId, UpdateModelCommand command);

    /**
     * 删除模型配置。
     */
    void delete(Long modelId);
}
