package com.agenthub.agent.mapper;

import com.agenthub.agent.dto.AgentItem;
import com.agenthub.agent.dto.AgentOptionItem;
import com.agenthub.agent.entity.AgentConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentConfigMapper extends BaseMapper<AgentConfig> {

    @Select("""
            select
                a.id as agentId,
                a.agent_key as agentKey,
                a.name as name,
                a.short_name as shortName,
                a.description as description,
                a.system_prompt as systemPrompt,
                a.default_model_id as defaultModelId,
                m.display_name as defaultModelName,
                a.enabled as enabled,
                a.sort as sort
            from agent_config a
            left join ai_model m on m.id = a.default_model_id
            order by a.sort asc, a.id asc
            """)
    List<AgentItem> selectJoinedItems();

    @Select("""
            select
                a.id as agentId,
                a.agent_key as agentKey,
                a.name as name,
                a.short_name as shortName,
                a.description as description,
                a.system_prompt as systemPrompt,
                a.default_model_id as defaultModelId,
                m.display_name as defaultModelName,
                a.enabled as enabled,
                a.sort as sort
            from agent_config a
            left join ai_model m on m.id = a.default_model_id
            where a.id = #{agentId}
            """)
    AgentItem selectJoinedById(@Param("agentId") Long agentId);

    @Select("""
            select
                a.id as agentId,
                a.agent_key as agentKey,
                a.name as name,
                a.short_name as shortName,
                a.description as description,
                a.default_model_id as defaultModelId,
                a.enabled as enabled,
                a.sort as sort
            from agent_config a
            where a.enabled = true
            order by a.sort asc, a.id asc
            """)
    List<AgentOptionItem> selectAvailableOptions();

    @Select("""
            select *
            from agent_config
            where agent_key = #{agentKey}
            limit 1
            """)
    AgentConfig selectByAgentKey(@Param("agentKey") String agentKey);
}
