package com.agenthub.system.user;

public record UserQuery(
        String username,
        String displayName,
        Boolean enabled,
        String roleCode
) {

    public UserQuery {
        username = normalize(username);
        displayName = normalize(displayName);
        roleCode = normalize(roleCode);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
