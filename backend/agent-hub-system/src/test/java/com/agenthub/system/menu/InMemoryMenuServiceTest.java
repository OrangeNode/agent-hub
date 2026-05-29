package com.agenthub.system.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryMenuServiceTest {

    @Test
    void shouldReturnCurrentUserMenuTreeByRoleCodes() {
        MenuService menuService = new InMemoryMenuService();

        List<MenuItem> adminMenus = menuService.listCurrentUserMenus(List.of("ADMIN"));
        List<MenuItem> userMenus = menuService.listCurrentUserMenus(List.of("USER"));

        assertThat(adminMenus).extracting(MenuItem::name).contains("dashboard", "system");
        assertThat(findChildren(adminMenus, "system")).extracting(MenuItem::name)
                .contains("systemUsers", "systemRoles", "systemMenus");

        assertThat(userMenus).extracting(MenuItem::name).containsExactly("dashboard");
    }

    private static List<MenuItem> findChildren(List<MenuItem> menus, String name) {
        return menus.stream()
                .filter(menu -> menu.name().equals(name))
                .findFirst()
                .orElseThrow()
                .children();
    }
}
