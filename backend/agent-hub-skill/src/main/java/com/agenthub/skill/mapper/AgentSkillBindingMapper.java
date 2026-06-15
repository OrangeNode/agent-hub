package com.agenthub.skill.mapper;

import com.agenthub.skill.entity.AgentSkillBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentSkillBindingMapper extends BaseMapper<AgentSkillBinding> {

    @Delete("delete from agent_skill_binding where skill_id = #{skillId}")
    int deleteBySkillId(Long skillId);

    @Delete("delete from agent_skill_binding where agent_id = #{agentId}")
    int deleteByAgentId(Long agentId);

    @Select("""
            select *
            from agent_skill_binding
            where agent_id = #{agentId}
            order by sort asc, id asc
            """)
    List<AgentSkillBinding> selectByAgentId(Long agentId);
}
