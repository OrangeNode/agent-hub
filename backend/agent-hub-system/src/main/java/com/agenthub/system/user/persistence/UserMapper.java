package com.agenthub.system.user.persistence;

import com.agenthub.system.user.UserQuery;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<SysUser> {

    @Select("""
            <script>
            SELECT DISTINCT u.id, u.username, u.display_name, u.password, u.enabled, u.created_by, u.created_at, u.updated_by, u.updated_at
            FROM sys_user u
            <where>
                <if test="query != null and query.username != null">
                    AND u.username ILIKE CONCAT('%', #{query.username}, '%')
                </if>
                <if test="query != null and query.displayName != null">
                    AND u.display_name ILIKE CONCAT('%', #{query.displayName}, '%')
                </if>
                <if test="query != null and query.enabled != null">
                    AND u.enabled = #{query.enabled}
                </if>
                <if test="query != null and query.roleCode != null">
                    AND EXISTS (
                        SELECT 1
                        FROM sys_user_role ur
                        JOIN sys_role r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id
                          AND r.code = #{query.roleCode}
                    )
                </if>
            </where>
            ORDER BY u.id
            </script>
            """)
    List<SysUser> selectByQuery(@Param("query") UserQuery query);

    @Select("""
            SELECT r.code
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
            ORDER BY r.id
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT ur.role_id
            FROM sys_user_role ur
            WHERE ur.user_id = #{userId}
            ORDER BY ur.role_id
            """)
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO sys_user_role (user_id, role_id)
            VALUES (#{userRole.userId}, #{userRole.roleId})
            """)
    int insertUserRole(@Param("userRole") SysUserRole userRole);

    @Delete("""
            DELETE FROM sys_user_role
            WHERE user_id = #{userId}
            """)
    int deleteUserRolesByUserId(@Param("userId") Long userId);

    @Delete("""
            DELETE FROM sys_user_role
            WHERE role_id = #{roleId}
            """)
    int deleteUserRolesByRoleId(@Param("roleId") Long roleId);

    @Select("""
            SELECT COUNT(1)
            FROM sys_user_role
            WHERE role_id = #{roleId}
            """)
    long countUsersByRoleId(@Param("roleId") Long roleId);
}
