package com.agenthub.system.menu.service;

import com.agenthub.system.menu.command.CreateMenuCommand;
import com.agenthub.system.menu.command.MenuOrderCommand;
import com.agenthub.system.menu.command.UpdateMenuCommand;
import com.agenthub.system.menu.dto.MenuItem;
import java.util.List;

public interface MenuService {

    List<MenuItem> listFlat();

    List<MenuItem> listTree();

    List<MenuItem> listCurrentUserMenus(List<String> roleCodes);

    List<MenuItem> listByIds(List<Long> menuIds);

    MenuItem create(CreateMenuCommand command);

    MenuItem update(Long menuId, UpdateMenuCommand command);

    void delete(Long menuId);

    void updateOrders(List<MenuOrderCommand> commands);
}
