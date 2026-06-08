package com.agenthub.sandbox.session.mapper;

import com.agenthub.sandbox.session.entity.SandboxSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SandboxSessionMapper extends BaseMapper<SandboxSession> {

    @Select("""
            select *
            from sandbox_session
            where sandbox_session_id = #{sandboxSessionId}
            limit 1
            """)
    SandboxSession selectBySandboxSessionId(@Param("sandboxSessionId") String sandboxSessionId);
}
