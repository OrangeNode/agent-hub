package com.agenthub.ai.model.mapper;

import com.agenthub.ai.model.dto.AiModelItem;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.ai.model.entity.AiModel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * AI 模型表数据访问接口，负责模型与供应商的联合查询。
 */
@Mapper
public interface AiModelMapper extends BaseMapper<AiModel> {

    /**
     * 查询模型管理列表，并带出供应商展示字段。
     */
    @Select("""
            select
                m.id as modelId,
                m.provider_id as providerId,
                p.code as providerCode,
                p.name as providerName,
                m.model_code as modelCode,
                m.display_name as displayName,
                m.capability_type as capabilityType,
                m.stream_supported as streamSupported,
                m.reasoning_supported as reasoningSupported,
                m.context_window as contextWindow,
                m.max_output_tokens as maxOutputTokens,
                m.default_model as defaultModel,
                m.enabled as enabled,
                m.sort as sort
            from ai_model m
            join ai_provider p on p.id = m.provider_id
            order by p.sort asc, p.id asc, m.sort asc, m.id asc
            """)
    List<AiModelItem> selectJoinedItems();

    /**
     * 按模型 ID 查询模型管理详情。
     */
    @Select("""
            select
                m.id as modelId,
                m.provider_id as providerId,
                p.code as providerCode,
                p.name as providerName,
                m.model_code as modelCode,
                m.display_name as displayName,
                m.capability_type as capabilityType,
                m.stream_supported as streamSupported,
                m.reasoning_supported as reasoningSupported,
                m.context_window as contextWindow,
                m.max_output_tokens as maxOutputTokens,
                m.default_model as defaultModel,
                m.enabled as enabled,
                m.sort as sort
            from ai_model m
            join ai_provider p on p.id = m.provider_id
            where m.id = #{modelId}
            """)
    AiModelItem selectJoinedById(@Param("modelId") Long modelId);

    /**
     * 查询模型和供应商都启用的可用模型列表。
     */
    @Select("""
            select
                m.id as modelId,
                m.provider_id as providerId,
                p.code as providerCode,
                p.name as providerName,
                m.model_code as modelCode,
                m.display_name as displayName,
                m.capability_type as capabilityType,
                m.stream_supported as streamSupported,
                m.reasoning_supported as reasoningSupported,
                m.context_window as contextWindow,
                m.max_output_tokens as maxOutputTokens,
                m.default_model as defaultModel,
                m.enabled as enabled,
                m.sort as sort
            from ai_model m
            join ai_provider p on p.id = m.provider_id
            where m.enabled = true and p.enabled = true
            order by p.sort asc, p.id asc, m.sort asc, m.id asc
            """)
    List<AiModelItem> selectAvailableItems();

    /**
     * 查询指定模型的运行时调用配置。
     */
    @Select("""
            select
                m.id as modelId,
                m.provider_id as providerId,
                p.code as providerCode,
                p.name as providerName,
                p.provider_type as providerType,
                p.protocol_type as protocolType,
                p.base_url as baseUrl,
                p.api_key_cipher as apiKeyCipher,
                p.auth_type as authType,
                p.api_version as apiVersion,
                p.extra_headers as extraHeaders,
                p.extra_body as extraBody,
                m.model_code as modelCode,
                m.display_name as displayName,
                m.stream_supported as streamSupported,
                m.reasoning_supported as reasoningSupported,
                m.context_window as contextWindow,
                m.max_output_tokens as maxOutputTokens,
                m.enabled as enabled,
                p.enabled as providerEnabled
            from ai_model m
            join ai_provider p on p.id = m.provider_id
            where m.id = #{modelId}
            """)
    AiModelRuntimeConfig selectRuntimeConfigById(@Param("modelId") Long modelId);

    /**
     * 查询默认模型的运行时调用配置。
     */
    @Select("""
            select
                m.id as modelId,
                m.provider_id as providerId,
                p.code as providerCode,
                p.name as providerName,
                p.provider_type as providerType,
                p.protocol_type as protocolType,
                p.base_url as baseUrl,
                p.api_key_cipher as apiKeyCipher,
                p.auth_type as authType,
                p.api_version as apiVersion,
                p.extra_headers as extraHeaders,
                p.extra_body as extraBody,
                m.model_code as modelCode,
                m.display_name as displayName,
                m.stream_supported as streamSupported,
                m.reasoning_supported as reasoningSupported,
                m.context_window as contextWindow,
                m.max_output_tokens as maxOutputTokens,
                m.enabled as enabled,
                p.enabled as providerEnabled
            from ai_model m
            join ai_provider p on p.id = m.provider_id
            where m.default_model = true
            order by m.sort asc, m.id asc
            limit 1
            """)
    AiModelRuntimeConfig selectDefaultRuntimeConfig();

    /**
     * 将指定模型以外的模型全部取消默认标记。
     */
    @Update("update ai_model set default_model = false where id <> #{modelId}")
    int clearDefaultModelExcept(@Param("modelId") Long modelId);
}
