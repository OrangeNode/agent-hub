package com.agenthub.system.menu;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.role.RoleItem;
import com.agenthub.system.support.InMemorySystemStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class InMemoryMenuService implements MenuService {

    private final InMemorySystemStore store;

    public InMemoryMenuService() {
        this(new InMemorySystemStore());
    }

    public InMemoryMenuService(InMemorySystemStore store) {
        this.store = store;
    }

    @Override
    public List<MenuItem> listFlat() {
        return store.menus().values().stream()
                .sorted(Comparator.comparingInt(MenuItem::sort).thenComparing(MenuItem::menuId))
                .map(this::withoutChildren)
                .toList();
    }

    @Override
    public List<MenuItem> listTree() {
        return buildTree(store.menus().values().stream().map(this::withoutChildren).toList());
    }

    @Override
    public List<MenuItem> listCurrentUserMenus(List<String> roleCodes) {
        Set<Long> grantedMenuIds = new LinkedHashSet<>();
        for (RoleItem role : store.roles().values()) {
            if (role.enabled() && roleCodes.contains(role.code())) {
                grantedMenuIds.addAll(role.menuIds());
            }
        }

        List<MenuItem> grantedMenus = store.menus().values().stream()
                .filter(menu -> grantedMenuIds.contains(menu.menuId()))
                .map(this::withoutChildren)
                .toList();

        return buildTree(grantedMenus);
    }

    @Override
    public List<MenuItem> listByIds(List<Long> menuIds) {
        return store.menus().values().stream()
                .filter(menu -> menuIds.contains(menu.menuId()))
                .map(this::withoutChildren)
                .toList();
    }

    @Override
    public MenuItem create(CreateMenuCommand command) {
        long menuId = store.nextMenuId();
        MenuItem menuItem = new MenuItem(
                menuId,
                command.parentId(),
                command.name(),
                command.title(),
                command.path(),
                command.component(),
                command.icon(),
                command.permission(),
                command.menuType(),
                command.visible(),
                command.sort(),
                List.of()
        );
        store.menus().put(menuId, menuItem);
        return withoutChildren(menuItem);
    }

    @Override
    public MenuItem update(Long menuId, UpdateMenuCommand command) {
        MenuItem existed = requireMenu(menuId);
        MenuItem updated = new MenuItem(
                existed.menuId(),
                existed.parentId(),
                existed.name(),
                command.title(),
                command.path(),
                command.component(),
                command.icon(),
                command.permission(),
                command.menuType(),
                command.visible(),
                command.sort(),
                List.of()
        );
        store.menus().put(menuId, updated);
        return updated;
    }

    @Override
    public void delete(Long menuId) {
        if (!store.menus().containsKey(menuId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在");
        }

        List<Long> childIds = store.menus().values().stream()
                .filter(menu -> menu.parentId().equals(menuId))
                .map(MenuItem::menuId)
                .toList();
        childIds.forEach(store.menus()::remove);
        store.menus().remove(menuId);
    }

    @Override
    public void updateOrders(List<MenuOrderCommand> commands) {
        for (MenuOrderCommand command : commands) {
            MenuItem existed = requireMenu(command.menuId());
            store.menus().put(command.menuId(), new MenuItem(
                    existed.menuId(),
                    existed.parentId(),
                    existed.name(),
                    existed.title(),
                    existed.path(),
                    existed.component(),
                    existed.icon(),
                    existed.permission(),
                    existed.menuType(),
                    existed.visible(),
                    command.sort(),
                    List.of()
            ));
        }
    }

    private MenuItem requireMenu(Long menuId) {
        MenuItem menuItem = store.menus().get(menuId);
        if (menuItem == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
        return menuItem;
    }

    private MenuItem withoutChildren(MenuItem menuItem) {
        return new MenuItem(
                menuItem.menuId(),
                menuItem.parentId(),
                menuItem.name(),
                menuItem.title(),
                menuItem.path(),
                menuItem.component(),
                menuItem.icon(),
                menuItem.permission(),
                menuItem.menuType(),
                menuItem.visible(),
                menuItem.sort(),
                List.of()
        );
    }

    private List<MenuItem> buildTree(List<MenuItem> flatMenus) {
        List<MenuItem> roots = flatMenus.stream()
                .filter(menu -> menu.parentId().equals(0L))
                .sorted(Comparator.comparingInt(MenuItem::sort).thenComparing(MenuItem::menuId))
                .toList();

        List<MenuItem> result = new ArrayList<>();
        for (MenuItem root : roots) {
            result.add(attachChildren(root, flatMenus));
        }
        return result;
    }

    private MenuItem attachChildren(MenuItem parent, List<MenuItem> flatMenus) {
        List<MenuItem> children = flatMenus.stream()
                .filter(menu -> menu.parentId().equals(parent.menuId()))
                .sorted(Comparator.comparingInt(MenuItem::sort).thenComparing(MenuItem::menuId))
                .map(menu -> attachChildren(menu, flatMenus))
                .toList();

        return new MenuItem(
                parent.menuId(),
                parent.parentId(),
                parent.name(),
                parent.title(),
                parent.path(),
                parent.component(),
                parent.icon(),
                parent.permission(),
                parent.menuType(),
                parent.visible(),
                parent.sort(),
                children
        );
    }
}
