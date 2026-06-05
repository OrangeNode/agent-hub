package com.agenthub.ai.provider.service.impl;

import com.agenthub.ai.provider.command.CreateProviderCommand;
import com.agenthub.ai.provider.command.UpdateProviderCommand;
import com.agenthub.ai.provider.dto.AiProviderConnectionTestResult;
import com.agenthub.ai.provider.dto.AiProviderItem;
import com.agenthub.ai.provider.entity.AiProvider;
import com.agenthub.ai.provider.mapper.AiProviderMapper;
import com.agenthub.ai.provider.service.AiProviderConnectivityTester;
import com.agenthub.ai.provider.service.AiProviderService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.io.Serializable;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiProviderServiceImpl implements AiProviderService {

    private static final String DEFAULT_JSON = "{}";

    private final AiProviderMapper providerMapper;
    private final AiProviderConnectivityTester connectivityTester;

    public AiProviderServiceImpl(AiProviderMapper providerMapper, AiProviderConnectivityTester connectivityTester) {
        this.providerMapper = providerMapper;
        this.connectivityTester = connectivityTester;
    }

    /**
     * 查询供应商管理列表，并按人工排序展示。
     */
    @Override
    public List<AiProviderItem> list() {
        return providerMapper.selectList(new LambdaQueryWrapper<AiProvider>()
                        .orderByAsc(AiProvider::getSort)
                        .orderByAsc(AiProvider::getId))
                .stream()
                .map(this::toItem)
                .toList();
    }

    /**
     * 查询指定供应商，不存在时抛出统一业务异常。
     */
    @Override
    public AiProviderItem requireById(Long providerId) {
        return toItem(requireProvider(providerId));
    }

    /**
     * 创建供应商配置，并保证供应商编码唯一。
     */
    @Override
    @Transactional
    public AiProviderItem create(CreateProviderCommand command) {
        boolean existed = providerMapper.selectCount(new LambdaQueryWrapper<AiProvider>()
                .eq(AiProvider::getCode, command.code())) > 0;
        if (existed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "供应商编码已存在");
        }

        AiProvider provider = new AiProvider();
        provider.setCode(command.code());
        provider.setName(command.name());
        provider.setProviderType(command.providerType());
        provider.setProtocolType(command.protocolType());
        provider.setBaseUrl(command.baseUrl());
        provider.setApiKeyCipher(encryptApiKey(command.apiKey()));
        provider.setAuthType(command.authType());
        provider.setApiVersion(command.apiVersion());
        provider.setExtraHeaders(defaultJson(command.extraHeaders()));
        provider.setExtraBody(defaultJson(command.extraBody()));
        provider.setEnabled(defaultEnabled(command.enabled()));
        provider.setSort(defaultSort(command.sort()));
        providerMapper.insert(provider);

        return toItem(requireProvider(provider.getId()));
    }

    /**
     * 更新供应商配置，API Key 为空时沿用原密钥密文。
     */
    @Override
    @Transactional
    public AiProviderItem update(Long providerId, UpdateProviderCommand command) {
        AiProvider existed = requireProvider(providerId);

        AiProvider updated = new AiProvider();
        updated.setId(existed.getId());
        updated.setCode(existed.getCode());
        updated.setName(command.name());
        updated.setProviderType(command.providerType());
        updated.setProtocolType(command.protocolType());
        updated.setBaseUrl(command.baseUrl());
        updated.setApiKeyCipher(hasText(command.apiKey()) ? encryptApiKey(command.apiKey()) : existed.getApiKeyCipher());
        updated.setAuthType(command.authType());
        updated.setApiVersion(command.apiVersion());
        updated.setExtraHeaders(defaultJson(command.extraHeaders()));
        updated.setExtraBody(defaultJson(command.extraBody()));
        updated.setEnabled(defaultEnabled(command.enabled()));
        updated.setSort(defaultSort(command.sort()));
        providerMapper.updateById(updated);

        return toItem(requireProvider(providerId));
    }

    /**
     * 删除供应商配置。
     */
    @Override
    @Transactional
    public void delete(Long providerId) {
        requireProvider(providerId);
        providerMapper.deleteById((Serializable) providerId);
    }

    @Override
    public AiProviderConnectionTestResult testConnection(Long providerId) {
        return connectivityTester.test(requireProvider(providerId));
    }

    /**
     * 查询供应商实体，不存在时统一抛出业务异常。
     */
    private AiProvider requireProvider(Long providerId) {
        AiProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在");
        }
        return provider;
    }

    private AiProviderItem toItem(AiProvider provider) {
        return new AiProviderItem(
                provider.getId(),
                provider.getCode(),
                provider.getName(),
                provider.getProviderType(),
                provider.getProtocolType(),
                provider.getBaseUrl(),
                provider.getAuthType(),
                provider.getApiVersion(),
                hasText(provider.getApiKeyCipher()),
                Boolean.TRUE.equals(provider.getEnabled()),
                provider.getSort() == null ? 0 : provider.getSort()
        );
    }

    /**
     * API Key 加密占位方法，后续接入 KMS 或本地加密组件时集中替换。
     */
    private String encryptApiKey(String apiKey) {
        return apiKey;
    }

    @SuppressWarnings("unused")
    /**
     * API Key 解密占位方法，保留给运行时调用真实密钥使用。
     */
    private String decryptApiKey(String apiKeyCipher) {
        return apiKeyCipher;
    }

    private boolean defaultEnabled(Boolean enabled) {
        return enabled == null || enabled;
    }

    private int defaultSort(Integer sort) {
        return sort == null ? 0 : sort;
    }

    /**
     * 额外请求头和请求体扩展默认落空对象，便于后续 JSON 合并。
     */
    private String defaultJson(String value) {
        return hasText(value) ? value : DEFAULT_JSON;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
