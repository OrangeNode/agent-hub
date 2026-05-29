package com.agenthub.system.user;

import java.util.List;

public record UserProfile(
        Long userId,
        String username,
        String displayName,
        boolean enabled,
        List<String> roles,
        List<String> permissions
) {
}
