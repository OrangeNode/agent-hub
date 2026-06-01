package com.agenthub.server.system;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.system.auth.AuthService;
import com.agenthub.system.menu.CreateMenuCommand;
import com.agenthub.system.menu.MenuItem;
import com.agenthub.system.menu.MenuOrderCommand;
import com.agenthub.system.menu.MenuService;
import com.agenthub.system.menu.UpdateMenuCommand;
import com.agenthub.system.permission.PermissionItem;
import com.agenthub.system.role.CreateRoleCommand;
import com.agenthub.system.role.RoleItem;
import com.agenthub.system.role.RoleService;
import com.agenthub.system.role.UpdateRoleCommand;
import com.agenthub.system.user.CreateUserCommand;
import com.agenthub.system.user.UpdateUserCommand;
import com.agenthub.system.user.UserProfile;
import com.agenthub.system.user.UserQuery;
import com.agenthub.system.user.UserService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final AuthService authService;
    private final UserService userService;
    private final RoleService roleService;
    private final MenuService menuService;

    public SystemController(
            AuthService authService,
            UserService userService,
            RoleService roleService,
            MenuService menuService
    ) {
        this.authService = authService;
        this.userService = userService;
        this.roleService = roleService;
        this.menuService = menuService;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserProfile>> listUsers(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String roleCode
    ) {
        return ApiResponse.success(userService.list(new UserQuery(username, displayName, enabled, roleCode)));
    }

    @PostMapping("/users")
    public ApiResponse<UserProfile> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.success(userService.create(new CreateUserCommand(
                request.username(),
                request.displayName(),
                request.password(),
                request.enabled(),
                request.roles()
        )));
    }

    @PutMapping("/users/{userId}")
    public ApiResponse<UserProfile> updateUser(@PathVariable Long userId, @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.success(userService.update(userId, new UpdateUserCommand(
                request.displayName(),
                request.enabled(),
                request.roles()
        )));
    }

    @PatchMapping("/users/{userId}/status")
    public ApiResponse<UserProfile> updateUserStatus(@PathVariable Long userId, @RequestParam boolean enabled) {
        return ApiResponse.success(userService.updateStatus(userId, enabled));
    }

    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId) {
        userService.delete(userId);
        return ApiResponse.success();
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleItem>> listRoles() {
        return ApiResponse.success(roleService.list());
    }

    @PostMapping("/roles")
    public ApiResponse<RoleItem> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.success(roleService.create(new CreateRoleCommand(
                request.code(),
                request.name(),
                request.enabled(),
                request.menuIds()
        )));
    }

    @PutMapping("/roles/{roleId}")
    public ApiResponse<RoleItem> updateRole(@PathVariable Long roleId, @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.success(roleService.update(roleId, new UpdateRoleCommand(
                request.name(),
                request.enabled(),
                request.menuIds()
        )));
    }

    @DeleteMapping("/roles/{roleId}")
    public ApiResponse<Void> deleteRole(@PathVariable Long roleId) {
        roleService.delete(roleId);
        return ApiResponse.success();
    }

    @GetMapping("/permissions")
    public ApiResponse<List<PermissionItem>> listPermissions() {
        return ApiResponse.success(authService.listPermissions());
    }

    @GetMapping("/menus")
    public ApiResponse<List<MenuItem>> listMenus() {
        return ApiResponse.success(menuService.listFlat());
    }

    @GetMapping("/menus/tree")
    public ApiResponse<List<MenuItem>> listMenuTree() {
        return ApiResponse.success(menuService.listTree());
    }

    @GetMapping("/menus/current-user")
    public ApiResponse<List<MenuItem>> currentUserMenus(Authentication authentication) {
        String username = resolveUsername(authentication);
        UserProfile currentUser = authService.currentUser(username);
        return ApiResponse.success(menuService.listCurrentUserMenus(currentUser.roles()));
    }

    @PostMapping("/menus")
    public ApiResponse<MenuItem> createMenu(@Valid @RequestBody CreateMenuRequest request) {
        return ApiResponse.success(menuService.create(new CreateMenuCommand(
                request.parentId(),
                request.name(),
                request.title(),
                request.path(),
                request.component(),
                request.icon(),
                request.permission(),
                request.menuType(),
                request.visible(),
                request.sort()
        )));
    }

    @PutMapping("/menus/{menuId}")
    public ApiResponse<MenuItem> updateMenu(@PathVariable Long menuId, @Valid @RequestBody UpdateMenuRequest request) {
        return ApiResponse.success(menuService.update(menuId, new UpdateMenuCommand(
                request.title(),
                request.path(),
                request.component(),
                request.icon(),
                request.permission(),
                request.menuType(),
                request.visible(),
                request.sort()
        )));
    }

    @PutMapping("/menus/order")
    public ApiResponse<Void> updateMenuOrders(@RequestBody List<MenuOrderRequest> requests) {
        menuService.updateOrders(requests.stream().map(request -> new MenuOrderCommand(request.menuId(), request.sort())).toList());
        return ApiResponse.success();
    }

    @DeleteMapping("/menus/{menuId}")
    public ApiResponse<Void> deleteMenu(@PathVariable Long menuId) {
        menuService.delete(menuId);
        return ApiResponse.success();
    }

    private String resolveUsername(Authentication authentication) {
        if (authentication == null) {
            authentication = SecurityContextHolder.getContext().getAuthentication();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof LoginUser loginUser) {
            return loginUser.username();
        }
        return String.valueOf(principal);
    }
}
