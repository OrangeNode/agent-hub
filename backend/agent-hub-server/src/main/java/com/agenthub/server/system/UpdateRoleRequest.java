package com.agenthub.server.system;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record UpdateRoleRequest(
        @NotBlank(message = "角色名称不能为空") String name,
        boolean enabled,
        List<Long> menuIds
) {
}
