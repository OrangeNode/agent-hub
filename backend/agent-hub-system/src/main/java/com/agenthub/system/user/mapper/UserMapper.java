package com.agenthub.system.user.mapper;

import com.agenthub.system.user.command.UserQuery;
import com.agenthub.system.user.entity.SysUser;
import com.agenthub.system.user.entity.SysUserRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户表和用户角色关系表的数据访问接口。
 */
@Mapper
public interface UserMapper extends BaseMapper<SysUser> {

    /**
     * 按用户管理页筛选条件查询用户，角色条件通过 EXISTS 避免重复用户行。
     */
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

    /**
     * 查询用户已绑定的角色编码。
     */
    @Select("""
            SELECT r.code
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
            ORDER BY r.id
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 查询用户已绑定的角色 ID。
     */
    @Select("""
            SELECT ur.role_id
            FROM sys_user_role ur
            WHERE ur.user_id = #{userId}
            ORDER BY ur.role_id
            """)
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    /**
     * 新增用户角色关系。
     */
    @Insert("""
            INSERT INTO sys_user_role (user_id, role_id)
            VALUES (#{userRole.userId}, #{userRole.roleId})
            """)
    int insertUserRole(@Param("userRole") SysUserRole userRole);

    /**
     * 删除指定用户的全部角色关系。
     */
    @Delete("""
            DELETE FROM sys_user_role
            WHERE user_id = #{userId}
            """)
    int deleteUserRolesByUserId(@Param("userId") Long userId);

    /**
     * 删除指定角色关联的全部用户关系。
     */
    @Delete("""
            DELETE FROM sys_user_role
            WHERE role_id = #{roleId}
            """)
    int deleteUserRolesByRoleId(@Param("roleId") Long roleId);

    /**
     * 统计指定角色已分配给多少用户。
     */
    @Select("""
            SELECT COUNT(1)
            FROM sys_user_role
            WHERE role_id = #{roleId}
            """)
    long countUsersByRoleId(@Param("roleId") Long roleId);
}
