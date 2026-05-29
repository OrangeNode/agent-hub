package com.agenthub.system.user;

import java.util.List;

public record UserAccount(
        Long userId,
        String username,
        String displayName,
        String password,
        boolean enabled,
        List<String> roles
) {
}
