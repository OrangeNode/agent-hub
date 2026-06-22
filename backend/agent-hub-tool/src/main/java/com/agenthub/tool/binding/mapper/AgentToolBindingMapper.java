package com.agenthub.tool.binding.mapper;

import com.agenthub.tool.binding.entity.AgentToolBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentToolBindingMapper extends BaseMapper<AgentToolBinding> {

    @Delete("""
            delete from agent_tool_binding
            where agent_id = #{agentId}
                and provider_type = #{providerType}
            """)
    int deleteByAgentIdAndProviderType(
            @Param("agentId") Long agentId,
            @Param("providerType") String providerType
    );

    @Select("""
            select *
            from agent_tool_binding
            where agent_id = #{agentId}
                and provider_type = #{providerType}
            order by sort asc, id asc
            """)
    List<AgentToolBinding> selectByAgentIdAndProviderType(
            @Param("agentId") Long agentId,
            @Param("providerType") String providerType
    );

    @Select("""
            select *
            from agent_tool_binding
            where agent_id = #{agentId}
                and tool_name = #{toolName}
            limit 1
            """)
    AgentToolBinding selectByAgentIdAndToolName(
            @Param("agentId") Long agentId,
            @Param("toolName") String toolName
    );
}
