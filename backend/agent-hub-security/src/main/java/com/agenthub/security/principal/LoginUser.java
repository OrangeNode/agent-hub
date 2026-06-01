package com.agenthub.security.principal;

import java.util.List;

public record LoginUser(Long userId, String username, List<String> roles) {
}
