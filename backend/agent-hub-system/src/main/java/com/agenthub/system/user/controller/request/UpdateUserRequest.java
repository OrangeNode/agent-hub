package com.agenthub.system.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record UpdateUserRequest(
        @NotBlank(message = "显示名称不能为空") String displayName,
        boolean enabled,
        @NotEmpty(message = "角色不能为空") List<String> roles
) {
}
