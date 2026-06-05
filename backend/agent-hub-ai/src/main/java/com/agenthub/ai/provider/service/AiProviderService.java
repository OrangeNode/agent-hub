package com.agenthub.ai.provider.service;

import com.agenthub.ai.provider.command.CreateProviderCommand;
import com.agenthub.ai.provider.command.UpdateProviderCommand;
import com.agenthub.ai.provider.dto.AiProviderConnectionTestResult;
import com.agenthub.ai.provider.dto.AiProviderItem;
import java.util.List;

public interface AiProviderService {

    /**
     * 查询全部 AI 供应商配置，按排序值和 ID 升序返回。
     */
    List<AiProviderItem> list();

    /**
     * 查询指定供应商，不存在时抛出业务异常。
     */
    AiProviderItem requireById(Long providerId);

    /**
     * 创建 AI 供应商配置。
     */
    AiProviderItem create(CreateProviderCommand command);

    /**
     * 更新 AI 供应商配置，未传入 API Key 时保留原密钥。
     */
    AiProviderItem update(Long providerId, UpdateProviderCommand command);

    /**
     * 删除 AI 供应商配置。
     */
    void delete(Long providerId);

    AiProviderConnectionTestResult testConnection(Long providerId);
}
