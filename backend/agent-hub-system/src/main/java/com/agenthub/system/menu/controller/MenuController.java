package com.agenthub.system.menu.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.system.auth.service.AuthService;
import com.agenthub.system.menu.controller.request.CreateMenuRequest;
import com.agenthub.system.menu.controller.request.MenuOrderRequest;
import com.agenthub.system.menu.controller.request.UpdateMenuRequest;
import com.agenthub.system.menu.command.CreateMenuCommand;
import com.agenthub.system.menu.command.MenuOrderCommand;
import com.agenthub.system.menu.command.UpdateMenuCommand;
import com.agenthub.system.menu.dto.MenuItem;
import com.agenthub.system.menu.service.MenuService;
import com.agenthub.system.user.dto.UserProfile;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/menus")
public class MenuController {

    private final AuthService authService;
    private final MenuService menuService;

    public MenuController(AuthService authService, MenuService menuService) {
        this.authService = authService;
        this.menuService = menuService;
    }

    /**
     * 查询菜单管理平铺列表。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('system:menu:list')")
    public ApiResponse<List<MenuItem>> listMenus() {
        return ApiResponse.success(menuService.listFlat());
    }

    /**
     * 查询菜单管理树形列表。
     */
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public ApiResponse<List<MenuItem>> listMenuTree() {
        return ApiResponse.success(menuService.listTree());
    }

    /**
     * 查询当前登录用户可访问的菜单树。
     */
    @GetMapping("/current-user")
    public ApiResponse<List<MenuItem>> currentUserMenus(Authentication authentication) {
        String username = resolveUsername(authentication);
        UserProfile currentUser = authService.currentUser(username);
        return ApiResponse.success(menuService.listCurrentUserMenus(currentUser.roles()));
    }

    /**
     * 创建系统菜单。
     */
    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:list')")
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

    /**
     * 更新系统菜单展示和权限配置。
     */
    @PutMapping("/{menuId}")
    @PreAuthorize("hasAuthority('system:menu:list')")
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

    /**
     * 批量更新菜单排序。
     */
    @PutMapping("/order")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public ApiResponse<Void> updateMenuOrders(@RequestBody List<MenuOrderRequest> requests) {
        menuService.updateOrders(requests.stream().map(request -> new MenuOrderCommand(request.menuId(), request.sort())).toList());
        return ApiResponse.success();
    }

    /**
     * 删除系统菜单及其子菜单。
     */
    @DeleteMapping("/{menuId}")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public ApiResponse<Void> deleteMenu(@PathVariable Long menuId) {
        menuService.delete(menuId);
        return ApiResponse.success();
    }

    /**
     * 从入参或安全上下文中解析当前登录用户名。
     */
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
