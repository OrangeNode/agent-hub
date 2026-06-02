package com.agenthub.system.role.controller.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateRoleRequest(
        @NotBlank(message = "角色编码不能为空") String code,
        @NotBlank(message = "角色名称不能为空") String name,
        boolean enabled,
        List<Long> menuIds
) {
}
