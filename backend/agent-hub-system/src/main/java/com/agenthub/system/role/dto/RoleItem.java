package com.agenthub.system.role.dto;

import java.util.List;

public record RoleItem(
        Long roleId,
        String code,
        String name,
        boolean enabled,
        List<Long> menuIds
) {
}
