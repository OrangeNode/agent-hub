package com.agenthub.system.user.command;

import java.util.List;

public record UpdateUserCommand(
        String displayName,
        boolean enabled,
        List<String> roles
) {
}
