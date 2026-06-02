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

    @GetMapping
    public ApiResponse<List<MenuItem>> listMenus() {
        return ApiResponse.success(menuService.listFlat());
    }

    @GetMapping("/tree")
    public ApiResponse<List<MenuItem>> listMenuTree() {
        return ApiResponse.success(menuService.listTree());
    }

    @GetMapping("/current-user")
    public ApiResponse<List<MenuItem>> currentUserMenus(Authentication authentication) {
        String username = resolveUsername(authentication);
        UserProfile currentUser = authService.currentUser(username);
        return ApiResponse.success(menuService.listCurrentUserMenus(currentUser.roles()));
    }

    @PostMapping
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

    @PutMapping("/{menuId}")
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

    @PutMapping("/order")
    public ApiResponse<Void> updateMenuOrders(@RequestBody List<MenuOrderRequest> requests) {
        menuService.updateOrders(requests.stream().map(request -> new MenuOrderCommand(request.menuId(), request.sort())).toList());
        return ApiResponse.success();
    }

    @DeleteMapping("/{menuId}")
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
