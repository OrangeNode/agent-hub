package com.agenthub.sandbox.session.mapper;

import com.agenthub.sandbox.session.entity.SandboxSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SandboxSessionMapper extends BaseMapper<SandboxSession> {

    @Select("""
            select *
            from sandbox_session
            where sandbox_session_id = #{sandboxSessionId}
            limit 1
            """)
    SandboxSession selectBySandboxSessionId(@Param("sandboxSessionId") String sandboxSessionId);

    @Select("""
            select *
            from sandbox_session
            where user_id = #{userId}
              and conversation_id = #{conversationId}
              and status in ('READY', 'RUNNING', 'ready', 'running')
            order by last_active_at desc, id desc
            limit 1
            """)
    SandboxSession selectActiveByConversation(@Param("userId") Long userId, @Param("conversationId") Long conversationId);

    @Select("""
            <script>
            select *
            from sandbox_session
            where 1 = 1
            <if test='status != null and status != ""'>
                and upper(status) = upper(#{status})
            </if>
            <if test='conversationId != null'>
                and conversation_id = #{conversationId}
            </if>
            <if test='sandboxSessionId != null and sandboxSessionId != ""'>
                and sandbox_session_id = #{sandboxSessionId}
            </if>
            order by created_at desc, id desc
            limit #{limit}
            offset #{offset}
            </script>
            """)
    List<SandboxSession> selectAdminSessions(
            @Param("status") String status,
            @Param("conversationId") Long conversationId,
            @Param("sandboxSessionId") String sandboxSessionId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            select *
            from sandbox_session
            where upper(status) in ('READY', 'RUNNING')
            order by created_at desc, id desc
            limit #{limit}
            """)
    List<SandboxSession> selectAdminOpenSessions(@Param("limit") int limit);

    @Select("""
            select count(*)
            from sandbox_session
            where upper(status) in ('READY', 'RUNNING')
            """)
    long countOpenSessions();

    @Update("""
            update sandbox_session
            set status = 'CLOSED',
                closed_at = #{closedAt}
            where sandbox_session_id = #{sandboxSessionId}
            """)
    void markClosedBySandboxSessionId(
            @Param("sandboxSessionId") String sandboxSessionId,
            @Param("closedAt") OffsetDateTime closedAt
    );

    @Update("""
            <script>
            update sandbox_session
            set status = 'CLOSED',
                closed_at = #{closedAt}
            where upper(status) in ('READY', 'RUNNING')
            <if test='runtimeSessionIds != null and runtimeSessionIds.size() > 0'>
                and sandbox_session_id not in
                <foreach collection='runtimeSessionIds' item='sessionId' open='(' separator=',' close=')'>
                    #{sessionId}
                </foreach>
            </if>
            </script>
            """)
    int markOpenSessionsMissingRuntimeClosed(
            @Param("runtimeSessionIds") List<String> runtimeSessionIds,
            @Param("closedAt") OffsetDateTime closedAt
    );
}
