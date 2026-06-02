package com.agenthub.system.user.dto;

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
