package com.agenthub.sandbox.artifact.mapper;

import com.agenthub.sandbox.artifact.entity.SandboxArtifact;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SandboxArtifactMapper extends BaseMapper<SandboxArtifact> {

    @Select("""
            select *
            from sandbox_artifact
            where conversation_id = #{conversationId}
                and relative_path = #{relativePath}
            order by version desc, id desc
            limit 1
            """)
    SandboxArtifact selectLatestByConversationAndPath(
            @Param("conversationId") Long conversationId,
            @Param("relativePath") String relativePath
    );
}
