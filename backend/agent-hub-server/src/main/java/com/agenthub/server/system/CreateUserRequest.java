package com.agenthub.server.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreateUserRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "显示名称不能为空") String displayName,
        @NotBlank(message = "密码不能为空") String password,
        boolean enabled,
        @NotEmpty(message = "角色不能为空") List<String> roles
) {
}
