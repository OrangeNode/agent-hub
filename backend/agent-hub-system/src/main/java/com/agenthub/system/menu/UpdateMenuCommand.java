package com.agenthub.system.menu;

public record UpdateMenuCommand(
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
