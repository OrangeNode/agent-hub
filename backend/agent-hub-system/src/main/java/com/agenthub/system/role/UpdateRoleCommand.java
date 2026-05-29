package com.agenthub.system.role;

import java.util.List;

public record UpdateRoleCommand(
        String name,
        boolean enabled,
        List<Long> menuIds
) {
}
