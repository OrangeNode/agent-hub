package com.agenthub.system.auth;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.menu.MenuItem;
import com.agenthub.system.permission.PermissionItem;
import com.agenthub.system.role.RoleItem;
import com.agenthub.system.user.UserProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryAuthService implements AuthService {

    private static final List<String> ADMIN_PERMISSIONS = List.of(
            "system:user:list",
            "system:role:list",
            "system:permission:list",
            "system:menu:list",
            "ai:provider:list",
            "ai:model:list",
            "mcp:server:list",
            "skill:list",
            "agent:manage",
            "chat:conversation:list",
            "chat:conversation:create"
    );

    private static final List<String> USER_PERMISSIONS = List.of(
            "chat:conversation:list",
            "chat:conversation:create"
    );

    private final Map<String, BootstrapUser> bootstrapUsers = new LinkedHashMap<>();

    public InMemoryAuthService() {
        bootstrapUsers.put("admin", new BootstrapUser(
                new UserProfile(1L, "admin", "平台管理员", List.of("ADMIN"), ADMIN_PERMISSIONS),
                "admin123"
        ));
        bootstrapUsers.put("user", new BootstrapUser(
                new UserProfile(2L, "user", "普通用户", List.of("USER"), USER_PERMISSIONS),
                "user123"
        ));
    }

    @Override
    public LoginResult login(LoginCommand command) {
        BootstrapUser bootstrapUser = bootstrapUsers.get(command.username());

        if (bootstrapUser == null || !bootstrapUser.password().equals(command.password())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }

        return new LoginResult(bootstrapUser.profile());
    }

    @Override
    public UserProfile currentUser(String username) {
        BootstrapUser bootstrapUser = bootstrapUsers.get(username);

        if (bootstrapUser == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "当前用户不存在");
        }

        return bootstrapUser.profile();
    }

    @Override
    public List<UserProfile> listUsers() {
        return bootstrapUsers.values().stream().map(BootstrapUser::profile).toList();
    }

    @Override
    public List<RoleItem> listRoles() {
        return List.of(
                new RoleItem("ADMIN", "管理员", ADMIN_PERMISSIONS),
                new RoleItem("USER", "普通用户", USER_PERMISSIONS)
        );
    }

    @Override
    public List<PermissionItem> listPermissions() {
        return ADMIN_PERMISSIONS.stream()
                .map(code -> new PermissionItem(code, code))
                .toList();
    }

    @Override
    public List<MenuItem> listMenus() {
        return List.of(
                new MenuItem("/system/users", "systemUsers", "用户管理"),
                new MenuItem("/ai/providers", "aiProviders", "AI 供应商"),
                new MenuItem("/agents", "agents", "Agent 管理")
        );
    }

    private record BootstrapUser(UserProfile profile, String password) {
    }
}
