package com.agenthub.system.auth.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.system.auth.service.AuthService;
import com.agenthub.system.permission.PermissionItem;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/permissions")
public class PermissionController {

    private final AuthService authService;

    public PermissionController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<List<PermissionItem>> listPermissions() {
        return ApiResponse.success(authService.listPermissions());
    }
}
