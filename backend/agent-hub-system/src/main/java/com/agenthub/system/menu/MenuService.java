package com.agenthub.system.menu;

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
