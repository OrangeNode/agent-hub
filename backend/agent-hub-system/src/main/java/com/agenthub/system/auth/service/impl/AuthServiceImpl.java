package com.agenthub.system.auth.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.auth.command.LoginCommand;
import com.agenthub.system.auth.dto.LoginResult;
import com.agenthub.system.auth.service.AuthService;
import com.agenthub.system.menu.dto.MenuItem;
import com.agenthub.system.menu.service.MenuService;
import com.agenthub.system.permission.PermissionItem;
import com.agenthub.system.role.dto.RoleItem;
import com.agenthub.system.role.service.RoleService;
import com.agenthub.system.user.dto.UserAccount;
import com.agenthub.system.user.dto.UserProfile;
import com.agenthub.system.user.service.UserService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final RoleService roleService;
    private final MenuService menuService;
    private final PasswordEncoder passwordEncoder;

    public AuthServiceImpl(
            UserService userService,
            RoleService roleService,
            MenuService menuService,
            PasswordEncoder passwordEncoder
    ) {
        this.userService = userService;
        this.roleService = roleService;
        this.menuService = menuService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 校验用户名和密码，登录成功后返回包含角色权限的用户画像。
     */
    @Override
    public LoginResult login(LoginCommand command) {
        UserAccount userAccount = userService.findAccountByUsername(command.username())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误"));

        if (!passwordEncoder.matches(command.password(), userAccount.password())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }

        return new LoginResult(buildProfile(userAccount));
    }

    /**
     * 重新从数据库装配当前用户画像，保证禁用状态和授权变更立即生效。
     */
    @Override
    public UserProfile currentUser(String username) {
        UserAccount userAccount = userService.findAccountByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "当前用户不存在"));
        return buildProfile(userAccount);
    }

    /**
     * 从菜单权限字段提取系统内可配置的权限点。
     */
    @Override
    public List<PermissionItem> listPermissions() {
        return menuService.listFlat().stream()
                .map(MenuItem::permission)
                .filter(permission -> permission != null && !permission.isBlank())
                .distinct()
                .map(permission -> new PermissionItem(permission, permission))
                .toList();
    }

    /**
     * 根据账号、角色和菜单授权组装登录态用户画像。
     */
    private UserProfile buildProfile(UserAccount userAccount) {
        if (!userAccount.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前用户已被禁用");
        }

        List<RoleItem> roles = roleService.findByCodes(userAccount.roles());
        List<String> roleCodes = roles.stream()
                .map(RoleItem::code)
                .distinct()
                .toList();
        Set<String> permissions = new LinkedHashSet<>();

        // 使用 LinkedHashSet 保留菜单遍历顺序，同时去除重复权限标识。
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
                roleCodes,
                List.copyOf(permissions)
        );
    }
}
