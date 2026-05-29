package com.agenthub.system.role;

import java.util.List;

public record RoleItem(String code, String name, List<String> permissions) {
}
