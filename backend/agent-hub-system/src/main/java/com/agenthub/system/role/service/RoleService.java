package com.agenthub.system.role.service;

import com.agenthub.system.role.command.CreateRoleCommand;
import com.agenthub.system.role.command.UpdateRoleCommand;
import com.agenthub.system.role.dto.RoleItem;
import java.util.List;

public interface RoleService {

    /**
     * 查询全部角色及其菜单授权关系。
     */
    List<RoleItem> list();

    /**
     * 按角色编码查询启用状态的角色。
     */
    List<RoleItem> findByCodes(List<String> codes);

    /**
     * 创建角色并保存菜单授权关系。
     */
    RoleItem create(CreateRoleCommand command);

    /**
     * 更新角色名称、状态和菜单授权关系。
     */
    RoleItem update(Long roleId, UpdateRoleCommand command);

    /**
     * 删除未分配给用户的角色。
     */
    void delete(Long roleId);
}
