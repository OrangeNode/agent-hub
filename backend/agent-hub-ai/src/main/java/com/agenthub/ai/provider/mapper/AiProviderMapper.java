package com.agenthub.ai.provider.mapper;

import com.agenthub.ai.provider.entity.AiProvider;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 供应商配置表数据访问接口，基础 CRUD 由 MyBatis-Plus 提供。
 */
@Mapper
public interface AiProviderMapper extends BaseMapper<AiProvider> {
}
