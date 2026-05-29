package com.agenthub.system.auth;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.menu.MenuItem;
import com.agenthub.system.menu.MenuService;
import com.agenthub.system.permission.PermissionItem;
import com.agenthub.system.role.RoleItem;
import com.agenthub.system.role.RoleService;
import com.agenthub.system.user.UserAccount;
import com.agenthub.system.user.UserProfile;
import com.agenthub.system.user.UserService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class InMemoryAuthService implements AuthService {

    private final UserService userService;
    private final RoleService roleService;
    private final MenuService menuService;

    public InMemoryAuthService(UserService userService, RoleService roleService, MenuService menuService) {
        this.userService = userService;
        this.roleService = roleService;
        this.menuService = menuService;
    }

    @Override
    public LoginResult login(LoginCommand command) {
        UserAccount userAccount = userService.findAccountByUsername(command.username())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误"));

        if (!userAccount.password().equals(command.password())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }

        if (!userAccount.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户已被禁用");
        }

        return new LoginResult(buildProfile(userAccount));
    }

    @Override
    public UserProfile currentUser(String username) {
        UserAccount userAccount = userService.findAccountByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "当前用户不存在"));
        return buildProfile(userAccount);
    }

    @Override
    public List<PermissionItem> listPermissions() {
        return menuService.listFlat().stream()
                .map(MenuItem::permission)
                .filter(permission -> permission != null && !permission.isBlank())
                .distinct()
                .map(permission -> new PermissionItem(permission, permission))
                .toList();
    }

    private UserProfile buildProfile(UserAccount userAccount) {
        List<RoleItem> roles = roleService.findByCodes(userAccount.roles());
        Set<String> permissions = new LinkedHashSet<>();

        for (RoleItem role : roles) {
            menuService.listByIds(role.menuIds()).stream()
                    .map(MenuItem::permission)
                    .filter(permission -> permission != null && !permission.isBlank())
                    .forEach(permissions::add);
        }

        return new UserProfile(
                userAccount.userId(),
                userAccount.username(),
                userAccount.displayName(),
                userAccount.enabled(),
                userAccount.roles(),
                List.copyOf(permissions)
        );
    }
}
