package com.agenthub.server.system;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.system.auth.AuthService;
import com.agenthub.system.menu.MenuItem;
import com.agenthub.system.permission.PermissionItem;
import com.agenthub.system.role.RoleItem;
import com.agenthub.system.user.UserProfile;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final AuthService authService;

    public SystemController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserProfile>> listUsers() {
        return ApiResponse.success(authService.listUsers());
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleItem>> listRoles() {
        return ApiResponse.success(authService.listRoles());
    }

    @GetMapping("/permissions")
    public ApiResponse<List<PermissionItem>> listPermissions() {
        return ApiResponse.success(authService.listPermissions());
    }

    @GetMapping("/menus")
    public ApiResponse<List<MenuItem>> listMenus() {
        return ApiResponse.success(authService.listMenus());
    }
}
