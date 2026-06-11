package com.agenthub.sandbox.artifact.mapper;

import com.agenthub.sandbox.artifact.entity.SandboxArtifact;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
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

    @Select("""
            <script>
            select *
            from sandbox_artifact
            where 1 = 1
            <if test='conversationId != null'>
                and conversation_id = #{conversationId}
            </if>
            <if test='sandboxSessionDbId != null'>
                and sandbox_session_id = #{sandboxSessionDbId}
            </if>
            order by created_at desc, id desc
            limit 500
            </script>
            """)
    List<SandboxArtifact> selectAdminArtifacts(
            @Param("conversationId") Long conversationId,
            @Param("sandboxSessionDbId") Long sandboxSessionDbId
    );
}
