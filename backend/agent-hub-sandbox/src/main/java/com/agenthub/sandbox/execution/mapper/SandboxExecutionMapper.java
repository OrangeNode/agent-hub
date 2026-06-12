package com.agenthub.sandbox.execution.mapper;

import com.agenthub.sandbox.execution.entity.SandboxExecution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SandboxExecutionMapper extends BaseMapper<SandboxExecution> {

    @Select("""
            <script>
            select *
            from sandbox_execution
            where 1 = 1
            <if test='conversationId != null'>
                and conversation_id = #{conversationId}
            </if>
            <if test='sandboxSessionDbId != null'>
                and sandbox_session_id = #{sandboxSessionDbId}
            </if>
            <if test='status != null and status != ""'>
                and upper(status) = upper(#{status})
            </if>
            order by started_at desc, id desc
            limit #{limit}
            offset #{offset}
            </script>
            """)
    List<SandboxExecution> selectAdminExecutions(
            @Param("conversationId") Long conversationId,
            @Param("sandboxSessionDbId") Long sandboxSessionDbId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            select count(*)
            from sandbox_execution
            where upper(status) = upper(#{status})
            """)
    long countAdminExecutionsByStatus(@Param("status") String status);

    @Select("""
            <script>
            select count(*)
            from sandbox_execution
            where upper(status) in
            <foreach collection='statuses' item='status' open='(' separator=',' close=')'>
                upper(#{status})
            </foreach>
            </script>
            """)
    long countAdminExecutionsByStatuses(@Param("statuses") List<String> statuses);
}
