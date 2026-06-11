package com.agenthub.system.auth.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.system.auth.service.AuthService;
import com.agenthub.system.permission.PermissionItem;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/permissions")
@PreAuthorize("hasAuthority('system:role:list')")
public class PermissionController {

    private final AuthService authService;

    public PermissionController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 查询系统内可配置的权限标识列表。
     */
    @GetMapping
    public ApiResponse<List<PermissionItem>> listPermissions() {
        return ApiResponse.success(authService.listPermissions());
    }
}
