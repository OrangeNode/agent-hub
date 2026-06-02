package com.agenthub.ai.provider.service.impl;

import com.agenthub.ai.provider.command.CreateProviderCommand;
import com.agenthub.ai.provider.command.UpdateProviderCommand;
import com.agenthub.ai.provider.dto.AiProviderItem;
import com.agenthub.ai.provider.entity.AiProvider;
import com.agenthub.ai.provider.mapper.AiProviderMapper;
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

    public AiProviderServiceImpl(AiProviderMapper providerMapper) {
        this.providerMapper = providerMapper;
    }

    @Override
    public List<AiProviderItem> list() {
        return providerMapper.selectList(new LambdaQueryWrapper<AiProvider>()
                        .orderByAsc(AiProvider::getSort)
                        .orderByAsc(AiProvider::getId))
                .stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    public AiProviderItem requireById(Long providerId) {
        return toItem(requireProvider(providerId));
    }

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

    @Override
    @Transactional
    public void delete(Long providerId) {
        requireProvider(providerId);
        providerMapper.deleteById((Serializable) providerId);
    }

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

    private String encryptApiKey(String apiKey) {
        return apiKey;
    }

    @SuppressWarnings("unused")
    private String decryptApiKey(String apiKeyCipher) {
        return apiKeyCipher;
    }

    private boolean defaultEnabled(Boolean enabled) {
        return enabled == null || enabled;
    }

    private int defaultSort(Integer sort) {
        return sort == null ? 0 : sort;
    }

    private String defaultJson(String value) {
        return hasText(value) ? value : DEFAULT_JSON;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
