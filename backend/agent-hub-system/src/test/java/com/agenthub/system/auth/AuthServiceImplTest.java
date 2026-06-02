package com.agenthub.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.system.auth.command.LoginCommand;
import com.agenthub.system.auth.dto.LoginResult;
import com.agenthub.system.auth.service.impl.AuthServiceImpl;
import com.agenthub.system.menu.dto.MenuItem;
import com.agenthub.system.menu.service.MenuService;
import com.agenthub.system.role.dto.RoleItem;
import com.agenthub.system.role.service.RoleService;
import com.agenthub.system.user.dto.UserAccount;
import com.agenthub.system.user.service.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceImplTest {

    @Test
    void shouldLoginWithBcryptAndReturnPermissions() {
        UserService userService = mock(UserService.class);
        RoleService roleService = mock(RoleService.class);
        MenuService menuService = mock(MenuService.class);
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        AuthServiceImpl authService = new AuthServiceImpl(userService, roleService, menuService, passwordEncoder);

        String encodedPassword = passwordEncoder.encode("admin123");
        UserAccount userAccount = new UserAccount(
                1L,
                "admin",
                "平台管理员",
                encodedPassword,
                true,
                List.of("ADMIN")
        );
        when(userService.findAccountByUsername("admin")).thenReturn(Optional.of(userAccount));
        when(roleService.findByCodes(List.of("ADMIN"))).thenReturn(List.of(
                new RoleItem(1L, "ADMIN", "平台管理员", true, List.of(10L, 11L))
        ));
        when(menuService.listByIds(List.of(10L, 11L))).thenReturn(List.of(
                new MenuItem(10L, 0L, "system", "系统管理", "/system", null, null, null, "MENU", true, 1, List.of()),
                new MenuItem(11L, 10L, "user", "用户管理", "/system/users", null, null, "system:user:list", "MENU", true, 2, List.of()),
                new MenuItem(12L, 10L, "role", "角色管理", "/system/roles", null, null, "system:role:list", "MENU", true, 3, List.of())
        ));

        LoginResult result = authService.login(new LoginCommand("admin", "admin123"));

        assertThat(result.profile().userId()).isEqualTo(1L);
        assertThat(result.profile().roles()).containsExactly("ADMIN");
        assertThat(result.profile().permissions()).containsExactly("system:user:list", "system:role:list");
    }
}
