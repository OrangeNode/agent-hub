package com.agenthub.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.exception.BusinessException;
import com.agenthub.system.menu.InMemoryMenuService;
import com.agenthub.system.menu.MenuService;
import com.agenthub.system.role.InMemoryRoleService;
import com.agenthub.system.role.RoleService;
import com.agenthub.system.user.InMemoryUserService;
import com.agenthub.system.user.UserService;
import org.junit.jupiter.api.Test;

class InMemoryAuthServiceTest {

    @Test
    void shouldLoginWithBootstrapAdmin() {
        AuthService authService = createAuthService();

        LoginResult result = authService.login(new LoginCommand("admin", "admin123"));

        assertThat(result.profile().username()).isEqualTo("admin");
        assertThat(result.profile().roles()).contains("ADMIN");
        assertThat(result.profile().permissions()).contains("system:user:list", "system:menu:list");
    }

    @Test
    void shouldRejectWrongPassword() {
        AuthService authService = createAuthService();

        assertThatThrownBy(() -> authService.login(new LoginCommand("admin", "bad")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    void shouldBuildCurrentUserPermissionsFromRoleMenus() {
        UserService userService = new InMemoryUserService();
        RoleService roleService = new InMemoryRoleService();
        MenuService menuService = new InMemoryMenuService();
        AuthService authService = new InMemoryAuthService(userService, roleService, menuService);

        assertThat(authService.currentUser("admin").permissions())
                .contains("system:user:list", "system:role:list", "system:menu:list");
        assertThat(authService.currentUser("user").permissions())
                .contains("dashboard:view")
                .doesNotContain("system:user:list");
    }

    private static AuthService createAuthService() {
        UserService userService = new InMemoryUserService();
        RoleService roleService = new InMemoryRoleService();
        MenuService menuService = new InMemoryMenuService();
        return new InMemoryAuthService(userService, roleService, menuService);
    }
}
