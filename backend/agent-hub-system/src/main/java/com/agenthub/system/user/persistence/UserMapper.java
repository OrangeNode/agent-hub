package com.agenthub.system.user.persistence;

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
