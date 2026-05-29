package com.agenthub.system.user;

import java.util.List;

public record CreateUserCommand(
        String username,
        String displayName,
        String password,
        boolean enabled,
        List<String> roles
) {
}
