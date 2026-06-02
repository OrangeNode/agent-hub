package com.agenthub.system.role.mapper;

import com.agenthub.system.role.entity.SysRole;
import com.agenthub.system.role.entity.SysRoleMenu;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoleMapper extends BaseMapper<SysRole> {

    @Select("""
            <script>
            SELECT *
            FROM sys_role
            WHERE code IN
            <foreach collection="codes" item="code" open="(" separator="," close=")">
                #{code}
            </foreach>
            ORDER BY id
            </script>
            """)
    List<SysRole> selectByCodes(@Param("codes") List<String> codes);

    @Select("""
            <script>
            SELECT *
            FROM sys_role
            WHERE enabled = TRUE
              AND code IN
            <foreach collection="codes" item="code" open="(" separator="," close=")">
                #{code}
            </foreach>
            ORDER BY id
            </script>
            """)
    List<SysRole> selectEnabledByCodes(@Param("codes") List<String> codes);

    @Select("""
            SELECT rm.menu_id
            FROM sys_role_menu rm
            WHERE rm.role_id = #{roleId}
            ORDER BY rm.menu_id
            """)
    List<Long> selectMenuIdsByRoleId(@Param("roleId") Long roleId);

    @Select("""
            <script>
            SELECT DISTINCT rm.menu_id
            FROM sys_role_menu rm
            JOIN sys_role r ON r.id = rm.role_id
            WHERE r.enabled = TRUE
              AND r.code IN
            <foreach collection="codes" item="code" open="(" separator="," close=")">
                #{code}
            </foreach>
            ORDER BY rm.menu_id
            </script>
            """)
    List<Long> selectMenuIdsByRoleCodes(@Param("codes") List<String> codes);

    @Insert("""
            INSERT INTO sys_role_menu (role_id, menu_id)
            VALUES (#{roleMenu.roleId}, #{roleMenu.menuId})
            """)
    int insertRoleMenu(@Param("roleMenu") SysRoleMenu roleMenu);

    @Delete("""
            DELETE FROM sys_role_menu
            WHERE role_id = #{roleId}
            """)
    int deleteRoleMenusByRoleId(@Param("roleId") Long roleId);

    @Delete("""
            <script>
            DELETE FROM sys_role_menu
            WHERE menu_id IN
            <foreach collection="menuIds" item="menuId" open="(" separator="," close=")">
                #{menuId}
            </foreach>
            </script>
            """)
    int deleteRoleMenusByMenuIds(@Param("menuIds") List<Long> menuIds);
}
