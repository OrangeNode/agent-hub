package com.agenthub.server.config;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AppBeans {

    @Bean
    UserService userService(UserMapper userMapper, RoleMapper roleMapper, PasswordEncoder passwordEncoder) {
        return new UserServiceImpl(userMapper, roleMapper, passwordEncoder);
    }

    @Bean
    RoleService roleService(RoleMapper roleMapper, UserMapper userMapper, MenuMapper menuMapper) {
        return new RoleServiceImpl(roleMapper, userMapper, menuMapper);
    }

    @Bean
    MenuService menuService(MenuMapper menuMapper, RoleMapper roleMapper) {
        return new MenuServiceImpl(menuMapper, roleMapper);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthService authService(
            UserService userService,
            RoleService roleService,
            MenuService menuService,
            PasswordEncoder passwordEncoder
    ) {
        return new AuthServiceImpl(userService, roleService, menuService, passwordEncoder);
    }

}
