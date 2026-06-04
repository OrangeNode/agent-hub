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

/**
 * 角色表和角色菜单关系表的数据访问接口。
 */
@Mapper
public interface RoleMapper extends BaseMapper<SysRole> {

    /**
     * 按角色编码批量查询角色。
     */
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

    /**
     * 按角色编码批量查询启用状态的角色。
     */
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

    /**
     * 查询角色已授权的菜单 ID。
     */
    @Select("""
            SELECT rm.menu_id
            FROM sys_role_menu rm
            WHERE rm.role_id = #{roleId}
            ORDER BY rm.menu_id
            """)
    List<Long> selectMenuIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 查询多个启用角色合并后的菜单授权 ID。
     */
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

    /**
     * 新增角色菜单关系。
     */
    @Insert("""
            INSERT INTO sys_role_menu (role_id, menu_id)
            VALUES (#{roleMenu.roleId}, #{roleMenu.menuId})
            """)
    int insertRoleMenu(@Param("roleMenu") SysRoleMenu roleMenu);

    /**
     * 删除指定角色的全部菜单授权关系。
     */
    @Delete("""
            DELETE FROM sys_role_menu
            WHERE role_id = #{roleId}
            """)
    int deleteRoleMenusByRoleId(@Param("roleId") Long roleId);

    /**
     * 删除指定菜单集合关联的角色授权关系。
     */
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
