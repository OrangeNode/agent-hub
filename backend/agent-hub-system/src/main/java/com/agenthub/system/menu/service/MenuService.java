package com.agenthub.system.menu.service;

import com.agenthub.system.menu.command.CreateMenuCommand;
import com.agenthub.system.menu.command.MenuOrderCommand;
import com.agenthub.system.menu.command.UpdateMenuCommand;
import com.agenthub.system.menu.dto.MenuItem;
import java.util.List;

public interface MenuService {

    /**
     * 查询平铺菜单列表，供管理端维护使用。
     */
    List<MenuItem> listFlat();

    /**
     * 查询完整菜单树，供管理端展示层级结构。
     */
    List<MenuItem> listTree();

    /**
     * 按用户角色查询可访问菜单，并补齐父级菜单。
     */
    List<MenuItem> listCurrentUserMenus(List<String> roleCodes);

    /**
     * 按菜单 ID 列表查询菜单项。
     */
    List<MenuItem> listByIds(List<Long> menuIds);

    /**
     * 创建菜单并校验父级菜单有效性。
     */
    MenuItem create(CreateMenuCommand command);

    /**
     * 更新菜单展示信息和权限标识。
     */
    MenuItem update(Long menuId, UpdateMenuCommand command);

    /**
     * 删除菜单及其所有子菜单，并清理角色授权关系。
     */
    void delete(Long menuId);

    /**
     * 批量更新菜单排序值。
     */
    void updateOrders(List<MenuOrderCommand> commands);
}
