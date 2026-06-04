package com.agenthub.system.role.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.system.role.controller.request.CreateRoleRequest;
import com.agenthub.system.role.controller.request.UpdateRoleRequest;
import com.agenthub.system.role.command.CreateRoleCommand;
import com.agenthub.system.role.command.UpdateRoleCommand;
import com.agenthub.system.role.dto.RoleItem;
import com.agenthub.system.role.service.RoleService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /**
     * 查询角色管理列表。
     */
    @GetMapping
    public ApiResponse<List<RoleItem>> listRoles() {
        return ApiResponse.success(roleService.list());
    }

    /**
     * 创建系统角色并配置菜单授权。
     */
    @PostMapping
    public ApiResponse<RoleItem> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.success(roleService.create(new CreateRoleCommand(
                request.code(),
                request.name(),
                request.enabled(),
                request.menuIds()
        )));
    }

    /**
     * 更新系统角色和菜单授权。
     */
    @PutMapping("/{roleId}")
    public ApiResponse<RoleItem> updateRole(@PathVariable Long roleId, @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.success(roleService.update(roleId, new UpdateRoleCommand(
                request.name(),
                request.enabled(),
                request.menuIds()
        )));
    }

    /**
     * 删除未被用户占用的系统角色。
     */
    @DeleteMapping("/{roleId}")
    public ApiResponse<Void> deleteRole(@PathVariable Long roleId) {
        roleService.delete(roleId);
        return ApiResponse.success();
    }
}
