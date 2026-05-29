package com.agenthub.system.menu;

public record CreateMenuCommand(
        Long parentId,
        String name,
        String title,
        String path,
        String component,
        String icon,
        String permission,
        String menuType,
        boolean visible,
        int sort
) {
}
