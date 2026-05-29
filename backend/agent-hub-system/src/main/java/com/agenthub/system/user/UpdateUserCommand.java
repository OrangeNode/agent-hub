package com.agenthub.system.user;

import java.util.List;

public record UpdateUserCommand(
        String displayName,
        boolean enabled,
        List<String> roles
) {
}
