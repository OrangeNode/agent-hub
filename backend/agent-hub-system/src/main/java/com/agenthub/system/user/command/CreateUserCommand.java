package com.agenthub.system.user.command;

import java.util.List;

public record CreateUserCommand(
        String username,
        String displayName,
        String password,
        boolean enabled,
        List<String> roles
) {
}
