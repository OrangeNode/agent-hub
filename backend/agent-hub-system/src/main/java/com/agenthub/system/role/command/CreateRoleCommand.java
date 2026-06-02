package com.agenthub.system.role.command;

import java.util.List;

public record CreateRoleCommand(
        String code,
        String name,
        boolean enabled,
        List<Long> menuIds
) {
}
