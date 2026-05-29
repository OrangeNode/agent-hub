package com.agenthub.system.menu;

import java.util.List;

public record MenuItem(
        Long menuId,
        Long parentId,
        String name,
        String title,
        String path,
        String component,
        String icon,
        String permission,
        String menuType,
        boolean visible,
        int sort,
        List<MenuItem> children
) {
}
