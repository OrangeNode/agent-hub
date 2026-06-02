package com.agenthub.system.menu.controller.request;

import jakarta.validation.constraints.NotBlank;

public record CreateMenuRequest(
        Long parentId,
        @NotBlank(message = "菜单名称不能为空") String name,
        @NotBlank(message = "菜单标题不能为空") String title,
        @NotBlank(message = "菜单路径不能为空") String path,
        String component,
        String icon,
        String permission,
        String menuType,
        boolean visible,
        int sort
) {
}
