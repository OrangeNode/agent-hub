package com.agenthub.system.support;

import com.agenthub.system.menu.MenuItem;
import com.agenthub.system.role.RoleItem;
import com.agenthub.system.user.UserAccount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class InMemorySystemStore {

    private final Map<Long, UserAccount> users = new LinkedHashMap<>();
    private final Map<Long, RoleItem> roles = new LinkedHashMap<>();
    private final Map<Long, MenuItem> menus = new LinkedHashMap<>();
    private final AtomicLong userIdSequence = new AtomicLong(2);
    private final AtomicLong roleIdSequence = new AtomicLong(2);
    private final AtomicLong menuIdSequence = new AtomicLong(5);

    public InMemorySystemStore() {
        initializeMenus();
        initializeRoles();
        initializeUsers();
    }

    public Map<Long, UserAccount> users() {
        return users;
    }

    public Map<Long, RoleItem> roles() {
        return roles;
    }

    public Map<Long, MenuItem> menus() {
        return menus;
    }

    public long nextUserId() {
        return userIdSequence.incrementAndGet();
    }

    public long nextRoleId() {
        return roleIdSequence.incrementAndGet();
    }

    public long nextMenuId() {
        return menuIdSequence.incrementAndGet();
    }

    private void initializeUsers() {
        users.put(1L, new UserAccount(1L, "admin", "平台管理员", "admin123", true, List.of("ADMIN")));
        users.put(2L, new UserAccount(2L, "user", "普通用户", "user123", true, List.of("USER")));
    }

    private void initializeRoles() {
        roles.put(1L, new RoleItem(1L, "ADMIN", "管理员", true, List.of(1L, 2L, 3L, 4L, 5L)));
        roles.put(2L, new RoleItem(2L, "USER", "普通用户", true, List.of(1L)));
    }

    private void initializeMenus() {
        menus.put(1L, new MenuItem(1L, 0L, "dashboard", "工作台", "/dashboard", "layout/dashboard", "DashboardOutlined", "dashboard:view", "MENU", true, 1, List.of()));
        menus.put(2L, new MenuItem(2L, 0L, "system", "系统管理", "/system", null, "SettingOutlined", null, "DIRECTORY", true, 2, List.of()));
        menus.put(3L, new MenuItem(3L, 2L, "systemUsers", "用户管理", "/system/users", "system/users/index", "UserOutlined", "system:user:list", "MENU", true, 1, List.of()));
        menus.put(4L, new MenuItem(4L, 2L, "systemRoles", "角色管理", "/system/roles", "system/roles/index", "TeamOutlined", "system:role:list", "MENU", true, 2, List.of()));
        menus.put(5L, new MenuItem(5L, 2L, "systemMenus", "菜单管理", "/system/menus", "system/menus/index", "MenuOutlined", "system:menu:list", "MENU", true, 3, List.of()));
    }
}
