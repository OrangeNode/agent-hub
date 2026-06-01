package com.agenthub.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenthub.system.auth.AuthService;
import com.agenthub.system.auth.AuthServiceImpl;
import com.agenthub.system.menu.MenuServiceImpl;
import com.agenthub.system.menu.MenuService;
import com.agenthub.system.menu.persistence.MenuMapper;
import com.agenthub.system.role.RoleServiceImpl;
import com.agenthub.system.role.RoleService;
import com.agenthub.system.role.persistence.RoleMapper;
import com.agenthub.system.user.UserServiceImpl;
import com.agenthub.system.user.UserService;
import com.agenthub.system.user.persistence.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AppBeansTest {

    @Test
    void shouldWireDatabaseBackedServicesByDefault() {
        AppBeans appBeans = new AppBeans();
        UserMapper userMapper = mock(UserMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        MenuMapper menuMapper = mock(MenuMapper.class);

        PasswordEncoder passwordEncoder = appBeans.passwordEncoder();
        UserService userService = appBeans.userService(userMapper, roleMapper, passwordEncoder);
        RoleService roleService = appBeans.roleService(roleMapper, userMapper, menuMapper);
        MenuService menuService = appBeans.menuService(menuMapper, roleMapper);
        AuthService authService = appBeans.authService(userService, roleService, menuService, passwordEncoder);

        assertThat(passwordEncoder).isNotNull();
        assertThat(userService).isInstanceOf(UserServiceImpl.class);
        assertThat(roleService).isInstanceOf(RoleServiceImpl.class);
        assertThat(menuService).isInstanceOf(MenuServiceImpl.class);
        assertThat(authService).isInstanceOf(AuthServiceImpl.class);
    }
}
