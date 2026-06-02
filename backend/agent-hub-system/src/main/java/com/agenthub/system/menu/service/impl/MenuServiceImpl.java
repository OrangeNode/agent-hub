package com.agenthub.system.menu.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.menu.command.CreateMenuCommand;
import com.agenthub.system.menu.command.MenuOrderCommand;
import com.agenthub.system.menu.command.UpdateMenuCommand;
import com.agenthub.system.menu.dto.MenuItem;
import com.agenthub.system.menu.entity.SysMenu;
import com.agenthub.system.menu.mapper.MenuMapper;
import com.agenthub.system.menu.service.MenuService;
import com.agenthub.system.role.mapper.RoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuServiceImpl implements MenuService {

    private final MenuMapper menuMapper;
    private final RoleMapper roleMapper;

    public MenuServiceImpl(MenuMapper menuMapper, RoleMapper roleMapper) {
        this.menuMapper = menuMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public List<MenuItem> listFlat() {
        return loadAllMenus().stream()
                .map(this::withoutChildren)
                .toList();
    }

    @Override
    public List<MenuItem> listTree() {
        return buildTree(listFlat());
    }

    @Override
    public List<MenuItem> listCurrentUserMenus(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        Set<Long> menuIds = new LinkedHashSet<>(roleMapper.selectMenuIdsByRoleCodes(roleCodes));
        if (menuIds.isEmpty()) {
            return List.of();
        }

        Map<Long, MenuItem> allMenus = loadAllMenus().stream()
                .map(this::withoutChildren)
                .collect(Collectors.toMap(MenuItem::menuId, Function.identity()));

        LinkedHashSet<Long> resolvedIds = new LinkedHashSet<>();
        for (Long menuId : menuIds) {
            collectWithParents(menuId, allMenus, resolvedIds);
        }

        List<MenuItem> grantedMenus = resolvedIds.stream()
                .map(allMenus::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        return buildTree(grantedMenus);
    }

    @Override
    public List<MenuItem> listByIds(List<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return List.of();
        }
        return menuMapper.selectBatchIds(menuIds).stream()
                .sorted(menuComparator())
                .map(this::withoutChildren)
                .toList();
    }

    @Override
    @Transactional
    public MenuItem create(CreateMenuCommand command) {
        Long parentId = validateParentId(command.parentId());

        SysMenu menu = new SysMenu();
        menu.setParentId(parentId);
        menu.setName(command.name());
        menu.setTitle(command.title());
        menu.setPath(command.path());
        menu.setComponent(command.component());
        menu.setIcon(command.icon());
        menu.setPermission(command.permission());
        menu.setMenuType(command.menuType());
        menu.setVisible(command.visible());
        menu.setSort(command.sort());
        menuMapper.insert(menu);
        return withoutChildren(requireMenu(menu.getId()));
    }

    @Override
    @Transactional
    public MenuItem update(Long menuId, UpdateMenuCommand command) {
        SysMenu existed = requireMenu(menuId);

        SysMenu updated = new SysMenu();
        updated.setId(existed.getId());
        updated.setParentId(existed.getParentId());
        updated.setName(existed.getName());
        updated.setTitle(command.title());
        updated.setPath(command.path());
        updated.setComponent(command.component());
        updated.setIcon(command.icon());
        updated.setPermission(command.permission());
        updated.setMenuType(command.menuType());
        updated.setVisible(command.visible());
        updated.setSort(command.sort());
        menuMapper.updateById(updated);
        return withoutChildren(requireMenu(menuId));
    }

    @Override
    @Transactional
    public void delete(Long menuId) {
        requireMenu(menuId);
        List<Long> targetIds = collectDescendantIds(menuId);
        roleMapper.deleteRoleMenusByMenuIds(targetIds);
        menuMapper.deleteBatchIds(targetIds);
    }

    @Override
    @Transactional
    public void updateOrders(List<MenuOrderCommand> commands) {
        if (commands == null) {
            return;
        }
        for (MenuOrderCommand command : commands) {
            requireMenu(command.menuId());
            SysMenu menu = new SysMenu();
            menu.setId(command.menuId());
            menu.setSort(command.sort());
            menuMapper.updateById(menu);
        }
    }

    private List<SysMenu> loadAllMenus() {
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>().orderByAsc(SysMenu::getSort, SysMenu::getId));
    }

    private Long validateParentId(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return 0L;
        }
        if (menuMapper.selectById(parentId) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上级菜单不存在");
        }
        return parentId;
    }

    private SysMenu requireMenu(Long menuId) {
        SysMenu menu = menuMapper.selectById(menuId);
        if (menu == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
        return menu;
    }

    private List<Long> collectDescendantIds(Long rootMenuId) {
        List<SysMenu> allMenus = loadAllMenus();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        ids.add(rootMenuId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (SysMenu menu : allMenus) {
                if (ids.contains(menu.getParentId()) && ids.add(menu.getId())) {
                    changed = true;
                }
            }
        }
        return List.copyOf(ids);
    }

    private void collectWithParents(Long menuId, Map<Long, MenuItem> allMenus, Set<Long> resolvedIds) {
        MenuItem menu = allMenus.get(menuId);
        if (menu == null) {
            return;
        }
        if (menu.parentId() != null && menu.parentId() > 0) {
            collectWithParents(menu.parentId(), allMenus, resolvedIds);
        }
        resolvedIds.add(menuId);
    }

    private MenuItem withoutChildren(SysMenu menu) {
        return new MenuItem(
                menu.getId(),
                menu.getParentId(),
                menu.getName(),
                menu.getTitle(),
                menu.getPath(),
                menu.getComponent(),
                menu.getIcon(),
                menu.getPermission(),
                menu.getMenuType(),
                Boolean.TRUE.equals(menu.getVisible()),
                menu.getSort() == null ? 0 : menu.getSort(),
                List.of()
        );
    }

    private List<MenuItem> buildTree(List<MenuItem> flatMenus) {
        List<MenuItem> roots = flatMenus.stream()
                .filter(menu -> menu.parentId().equals(0L))
                .sorted(menuItemComparator())
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
                .sorted(menuItemComparator())
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

    private Comparator<SysMenu> menuComparator() {
        return Comparator.comparing((SysMenu menu) -> menu.getSort() == null ? 0 : menu.getSort())
                .thenComparing(SysMenu::getId);
    }

    private Comparator<MenuItem> menuItemComparator() {
        return Comparator.comparingInt(MenuItem::sort).thenComparing(MenuItem::menuId);
    }
}
