package com.agenthub.server.support;

import com.agenthub.server.config.SecurityConfig;
import com.agenthub.server.exception.GlobalExceptionHandler;
import com.agenthub.system.auth.controller.AuthController;
import com.agenthub.system.auth.controller.PermissionController;
import com.agenthub.system.menu.controller.MenuController;
import com.agenthub.system.role.controller.RoleController;
import com.agenthub.system.user.controller.UserController;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        AuthController.class,
        PermissionController.class,
        UserController.class,
        RoleController.class,
        MenuController.class
})
public class ServerWebTestApplication {
}
