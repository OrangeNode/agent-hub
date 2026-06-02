package com.agenthub.system.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.menu.command.CreateMenuCommand;
import com.agenthub.system.menu.dto.MenuItem;
import com.agenthub.system.menu.entity.SysMenu;
import com.agenthub.system.menu.mapper.MenuMapper;
import com.agenthub.system.menu.service.impl.MenuServiceImpl;
import com.agenthub.system.role.command.CreateRoleCommand;
import com.agenthub.system.role.dto.RoleItem;
import com.agenthub.system.role.entity.SysRole;
import com.agenthub.system.role.entity.SysRoleMenu;
import com.agenthub.system.role.mapper.RoleMapper;
import com.agenthub.system.role.service.impl.RoleServiceImpl;
import com.agenthub.system.user.command.CreateUserCommand;
import com.agenthub.system.user.dto.UserAccount;
import com.agenthub.system.user.entity.SysUser;
import com.agenthub.system.user.entity.SysUserRole;
import com.agenthub.system.user.mapper.UserMapper;
import com.agenthub.system.user.service.impl.UserServiceImpl;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserRoleMenuServiceTest {

    @Test
    void shouldLoadAdminRolesAndMenusFromDatabaseServices() {
        TestContext context = new TestContext();

        Optional<UserAccount> account = context.userService.findAccountByUsername("admin");

        assertThat(account).isPresent();
        assertThat(account.orElseThrow().roles()).containsExactly("ADMIN");

        List<RoleItem> roles = context.roleService.findByCodes(account.orElseThrow().roles());
        assertThat(roles).extracting(RoleItem::code).containsExactly("ADMIN");

        List<MenuItem> menus = context.menuService.listCurrentUserMenus(account.orElseThrow().roles());
        assertThat(menus).isNotEmpty();
        assertThat(menus).extracting(MenuItem::name).contains("dashboard", "system");
    }

    @Test
    void shouldRejectBindingDisabledRoleToUser() {
        TestContext context = new TestContext();

        assertThatThrownBy(() -> context.userService.create(new CreateUserCommand(
                "operator",
                "运营人员",
                "operator123",
                true,
                List.of("DISABLED")
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色已被禁用");
    }

    @Test
    void shouldRejectBindingMissingMenuToRole() {
        TestContext context = new TestContext();

        assertThatThrownBy(() -> context.roleService.create(new CreateRoleCommand(
                "OPS",
                "运营角色",
                true,
                List.of(1L, 999L)
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("菜单不存在");
    }

    @Test
    void shouldEncodePasswordWhenCreatingUser() {
        TestContext context = new TestContext();

        context.userService.create(new CreateUserCommand(
                "operator",
                "运营人员",
                "operator123",
                true,
                List.of("USER")
        ));

        SysUser createdUser = context.db.findUserByUsername("operator");
        assertThat(createdUser).isNotNull();
        assertThat(createdUser.getPassword()).isNotEqualTo("operator123");
        assertThat(context.passwordEncoder.matches("operator123", createdUser.getPassword())).isTrue();
    }

    @Test
    void shouldRejectDeletingRoleThatIsBoundByUsers() {
        TestContext context = new TestContext();

        assertThatThrownBy(() -> context.roleService.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前角色已分配给用户");
    }

    @Test
    void shouldRejectCreatingMenuWhenParentMenuDoesNotExist() {
        TestContext context = new TestContext();

        assertThatThrownBy(() -> context.menuService.create(new CreateMenuCommand(
                999L,
                "orphanMenu",
                "孤立菜单",
                "/orphan",
                "system/orphan/index",
                null,
                null,
                "MENU",
                true,
                9
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上级菜单不存在");
    }

    private static final class TestContext {

        private final FakeDb db = new FakeDb();
        private final UserMapper userMapper = createUserMapper(db);
        private final RoleMapper roleMapper = createRoleMapper(db);
        private final MenuMapper menuMapper = createMenuMapper(db);
        private final PasswordEncoder passwordEncoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "ENC(" + rawPassword + ")";
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return Objects.equals(encode(rawPassword), encodedPassword);
            }
        };
        private final UserServiceImpl userService = new UserServiceImpl(userMapper, roleMapper, passwordEncoder);
        private final RoleServiceImpl roleService = new RoleServiceImpl(roleMapper, userMapper, menuMapper);
        private final MenuServiceImpl menuService = new MenuServiceImpl(menuMapper, roleMapper);

        private TestContext() {
            seed();
        }

        private void seed() {
            db.addMenu(1L, 0L, "dashboard", "工作台", "/dashboard", "MENU", 1);
            db.addMenu(2L, 0L, "system", "系统管理", "/system", "DIRECTORY", 2);
            db.addMenu(3L, 2L, "systemUsers", "用户管理", "/system/users", "MENU", 1);
            db.addMenu(4L, 2L, "systemRoles", "角色管理", "/system/roles", "MENU", 2);
            db.addMenu(5L, 2L, "systemMenus", "菜单管理", "/system/menus", "MENU", 3);

            db.addRole(1L, "ADMIN", "平台管理员", true, List.of(1L, 2L, 3L, 4L, 5L));
            db.addRole(2L, "USER", "普通用户", true, List.of(1L));
            db.addRole(3L, "DISABLED", "禁用角色", false, List.of(1L));

            db.addUser(1L, "admin", "平台管理员", "hashed", true, List.of(1L));
        }
    }

    private static UserMapper createUserMapper(FakeDb db) {
        return (UserMapper) Proxy.newProxyInstance(
                UserRoleMenuServiceTest.class.getClassLoader(),
                new Class<?>[]{UserMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectOne" -> db.findUserByUsername("admin");
                    case "selectById" -> db.users.get((Long) args[0]);
                    case "selectList" -> new ArrayList<>(db.users.values());
                    case "selectCount" -> 0L;
                    case "insert" -> {
                        SysUser user = (SysUser) args[0];
                        user.setId(db.nextUserId++);
                        db.users.put(user.getId(), copyUser(user));
                        yield 1;
                    }
                    case "updateById" -> {
                        SysUser incoming = (SysUser) args[0];
                        SysUser current = db.users.get(incoming.getId());
                        if (current != null) {
                            db.users.put(incoming.getId(), mergeUser(current, incoming));
                        }
                        yield 1;
                    }
                    case "update" -> 1;
                    case "deleteById" -> {
                        db.users.remove((Long) args[0]);
                        db.userRoles.remove((Long) args[0]);
                        yield 1;
                    }
                    case "selectRoleCodesByUserId" -> db.roleCodesByUserId((Long) args[0]);
                    case "selectRoleIdsByUserId" -> new ArrayList<>(db.userRoles.getOrDefault((Long) args[0], List.of()));
                    case "insertUserRole" -> {
                        SysUserRole relation = args[0] instanceof SysUserRole userRole
                                ? userRole
                                : (SysUserRole) args[1];
                        db.userRoles.computeIfAbsent(relation.getUserId(), key -> new ArrayList<>()).add(relation.getRoleId());
                        yield 1;
                    }
                    case "deleteUserRolesByUserId" -> {
                        Long userId = args[0] instanceof Long value ? value : (Long) args[1];
                        db.userRoles.remove(userId);
                        yield 1;
                    }
                    case "deleteUserRolesByRoleId" -> {
                        Long roleId = args[0] instanceof Long value ? value : (Long) args[1];
                        db.userRoles.values().forEach(roleIds -> roleIds.removeIf(id -> Objects.equals(id, roleId)));
                        yield 1;
                    }
                    case "countUsersByRoleId" -> {
                        Long roleId = args[0] instanceof Long value ? value : (Long) args[1];
                        yield db.countUsersByRoleId(roleId);
                    }
                    case "selectByQuery" -> {
                        Object queryArg = args[0] != null ? args[0] : args[1];
                        yield db.findUsersByQuery(queryArg);
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static RoleMapper createRoleMapper(FakeDb db) {
        return (RoleMapper) Proxy.newProxyInstance(
                UserRoleMenuServiceTest.class.getClassLoader(),
                new Class<?>[]{RoleMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectByCodes" -> db.rolesByCodes((List<String>) valueArg(args), false);
                    case "selectEnabledByCodes" -> db.rolesByCodes((List<String>) valueArg(args), true);
                    case "selectMenuIdsByRoleId" -> new ArrayList<>(db.roleMenus.getOrDefault((Long) valueArg(args), List.of()));
                    case "selectMenuIdsByRoleCodes" -> db.menuIdsByRoleCodes((List<String>) valueArg(args));
                    case "selectList" -> new ArrayList<>(db.roles.values());
                    case "selectCount" -> 0L;
                    case "selectById" -> db.roles.get((Long) args[0]);
                    case "insert" -> {
                        SysRole role = (SysRole) args[0];
                        role.setId(db.nextRoleId++);
                        db.roles.put(role.getId(), copyRole(role));
                        yield 1;
                    }
                    case "updateById" -> {
                        SysRole incoming = (SysRole) args[0];
                        SysRole current = db.roles.get(incoming.getId());
                        if (current != null) {
                            db.roles.put(incoming.getId(), mergeRole(current, incoming));
                        }
                        yield 1;
                    }
                    case "deleteById" -> {
                        db.roles.remove((Long) args[0]);
                        db.roleMenus.remove((Long) args[0]);
                        yield 1;
                    }
                    case "insertRoleMenu" -> {
                        SysRoleMenu relation = args[0] instanceof SysRoleMenu roleMenu
                                ? roleMenu
                                : (SysRoleMenu) args[1];
                        db.roleMenus.computeIfAbsent(relation.getRoleId(), key -> new ArrayList<>()).add(relation.getMenuId());
                        yield 1;
                    }
                    case "deleteRoleMenusByRoleId" -> {
                        Long roleId = (Long) valueArg(args);
                        db.roleMenus.remove(roleId);
                        yield 1;
                    }
                    case "deleteRoleMenusByMenuIds" -> {
                        List<Long> menuIds = (List<Long>) valueArg(args);
                        db.roleMenus.values().forEach(ids -> ids.removeIf(menuIds::contains));
                        yield 1;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static MenuMapper createMenuMapper(FakeDb db) {
        return (MenuMapper) Proxy.newProxyInstance(
                UserRoleMenuServiceTest.class.getClassLoader(),
                new Class<?>[]{MenuMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> new ArrayList<>(db.menus.values());
                    case "selectBatchIds" -> db.findMenusByIds((List<Long>) args[0]);
                    case "selectById" -> db.menus.get((Long) args[0]);
                    case "insert" -> {
                        SysMenu menu = (SysMenu) args[0];
                        menu.setId(db.nextMenuId++);
                        db.menus.put(menu.getId(), copyMenu(menu));
                        yield 1;
                    }
                    case "updateById" -> {
                        SysMenu incoming = (SysMenu) args[0];
                        SysMenu current = db.menus.get(incoming.getId());
                        if (current != null) {
                            db.menus.put(incoming.getId(), mergeMenu(current, incoming));
                        }
                        yield 1;
                    }
                    case "deleteBatchIds" -> {
                        for (Long id : (List<Long>) args[0]) {
                            db.menus.remove(id);
                        }
                        yield 1;
                    }
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object valueArg(Object[] args) {
        return args.length == 1 ? args[0] : args[1];
    }

    private static SysUser copyUser(SysUser source) {
        SysUser target = new SysUser();
        target.setId(source.getId());
        target.setUsername(source.getUsername());
        target.setDisplayName(source.getDisplayName());
        target.setPassword(source.getPassword());
        target.setEnabled(source.getEnabled());
        return target;
    }

    private static SysUser mergeUser(SysUser current, SysUser incoming) {
        SysUser merged = copyUser(current);
        if (incoming.getUsername() != null) {
            merged.setUsername(incoming.getUsername());
        }
        if (incoming.getDisplayName() != null) {
            merged.setDisplayName(incoming.getDisplayName());
        }
        if (incoming.getPassword() != null) {
            merged.setPassword(incoming.getPassword());
        }
        if (incoming.getEnabled() != null) {
            merged.setEnabled(incoming.getEnabled());
        }
        return merged;
    }

    private static SysRole copyRole(SysRole source) {
        SysRole target = new SysRole();
        target.setId(source.getId());
        target.setCode(source.getCode());
        target.setName(source.getName());
        target.setEnabled(source.getEnabled());
        return target;
    }

    private static SysRole mergeRole(SysRole current, SysRole incoming) {
        SysRole merged = copyRole(current);
        if (incoming.getCode() != null) {
            merged.setCode(incoming.getCode());
        }
        if (incoming.getName() != null) {
            merged.setName(incoming.getName());
        }
        if (incoming.getEnabled() != null) {
            merged.setEnabled(incoming.getEnabled());
        }
        return merged;
    }

    private static SysMenu copyMenu(SysMenu source) {
        SysMenu target = new SysMenu();
        target.setId(source.getId());
        target.setParentId(source.getParentId());
        target.setName(source.getName());
        target.setTitle(source.getTitle());
        target.setPath(source.getPath());
        target.setComponent(source.getComponent());
        target.setIcon(source.getIcon());
        target.setPermission(source.getPermission());
        target.setMenuType(source.getMenuType());
        target.setVisible(source.getVisible());
        target.setSort(source.getSort());
        return target;
    }

    private static SysMenu mergeMenu(SysMenu current, SysMenu incoming) {
        SysMenu merged = copyMenu(current);
        if (incoming.getParentId() != null) {
            merged.setParentId(incoming.getParentId());
        }
        if (incoming.getName() != null) {
            merged.setName(incoming.getName());
        }
        if (incoming.getTitle() != null) {
            merged.setTitle(incoming.getTitle());
        }
        if (incoming.getPath() != null) {
            merged.setPath(incoming.getPath());
        }
        if (incoming.getComponent() != null) {
            merged.setComponent(incoming.getComponent());
        }
        if (incoming.getIcon() != null) {
            merged.setIcon(incoming.getIcon());
        }
        if (incoming.getPermission() != null) {
            merged.setPermission(incoming.getPermission());
        }
        if (incoming.getMenuType() != null) {
            merged.setMenuType(incoming.getMenuType());
        }
        if (incoming.getVisible() != null) {
            merged.setVisible(incoming.getVisible());
        }
        if (incoming.getSort() != null) {
            merged.setSort(incoming.getSort());
        }
        return merged;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType.equals(boolean.class)) {
            return false;
        }
        if (returnType.equals(int.class)) {
            return 0;
        }
        if (returnType.equals(long.class)) {
            return 0L;
        }
        return null;
    }

    private static final class FakeDb {

        private final Map<Long, SysUser> users = new LinkedHashMap<>();
        private final Map<Long, SysRole> roles = new LinkedHashMap<>();
        private final Map<Long, SysMenu> menus = new LinkedHashMap<>();
        private final Map<Long, List<Long>> userRoles = new LinkedHashMap<>();
        private final Map<Long, List<Long>> roleMenus = new LinkedHashMap<>();
        private long nextUserId = 10L;
        private long nextRoleId = 10L;
        private long nextMenuId = 10L;

        private void addUser(Long id, String username, String displayName, String password, boolean enabled, List<Long> roleIds) {
            SysUser user = new SysUser();
            user.setId(id);
            user.setUsername(username);
            user.setDisplayName(displayName);
            user.setPassword(password);
            user.setEnabled(enabled);
            users.put(id, user);
            userRoles.put(id, new ArrayList<>(roleIds));
        }

        private void addRole(Long id, String code, String name, boolean enabled, List<Long> menuIds) {
            SysRole role = new SysRole();
            role.setId(id);
            role.setCode(code);
            role.setName(name);
            role.setEnabled(enabled);
            roles.put(id, role);
            roleMenus.put(id, new ArrayList<>(menuIds));
        }

        private void addMenu(Long id, Long parentId, String name, String title, String path, String menuType, int sort) {
            SysMenu menu = new SysMenu();
            menu.setId(id);
            menu.setParentId(parentId);
            menu.setName(name);
            menu.setTitle(title);
            menu.setPath(path);
            menu.setMenuType(menuType);
            menu.setVisible(true);
            menu.setSort(sort);
            menus.put(id, menu);
        }

        private SysUser findUserByUsername(String username) {
            return users.values().stream()
                    .filter(user -> Objects.equals(user.getUsername(), username))
                    .findFirst()
                    .orElse(null);
        }

        private List<Object> findUsersByQuery(Object queryArg) {
            return new ArrayList<>(users.values());
        }

        private List<String> roleCodesByUserId(Long userId) {
            return userRoles.getOrDefault(userId, List.of()).stream()
                    .map(roles::get)
                    .filter(Objects::nonNull)
                    .map(SysRole::getCode)
                    .toList();
        }

        private long countUsersByRoleId(Long roleId) {
            return userRoles.values().stream()
                    .filter(roleIds -> roleIds.contains(roleId))
                    .count();
        }

        private List<SysRole> rolesByCodes(List<String> codes, boolean enabledOnly) {
            Set<String> codeSet = new LinkedHashSet<>(codes);
            return roles.values().stream()
                    .filter(role -> codeSet.contains(role.getCode()))
                    .filter(role -> !enabledOnly || Boolean.TRUE.equals(role.getEnabled()))
                    .map(UserRoleMenuServiceTest::copyRole)
                    .toList();
        }

        private List<Long> menuIdsByRoleCodes(List<String> codes) {
            Set<String> codeSet = new LinkedHashSet<>(codes);
            return roles.values().stream()
                    .filter(role -> codeSet.contains(role.getCode()))
                    .filter(role -> Boolean.TRUE.equals(role.getEnabled()))
                    .flatMap(role -> roleMenus.getOrDefault(role.getId(), List.of()).stream())
                    .distinct()
                    .toList();
        }

        private List<SysMenu> findMenusByIds(List<Long> menuIds) {
            Set<Long> idSet = new LinkedHashSet<>(menuIds);
            return menus.values().stream()
                    .filter(menu -> idSet.contains(menu.getId()))
                    .map(UserRoleMenuServiceTest::copyMenu)
                    .toList();
        }
    }
}
