package com.agenthub.server.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenthub.server.exception.GlobalExceptionHandler;
import com.agenthub.system.auth.AuthService;
import com.agenthub.system.auth.InMemoryAuthService;
import com.agenthub.system.menu.InMemoryMenuService;
import com.agenthub.system.menu.MenuService;
import com.agenthub.system.role.InMemoryRoleService;
import com.agenthub.system.role.RoleService;
import com.agenthub.system.user.InMemoryUserService;
import com.agenthub.system.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class SystemControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        UserService userService = new InMemoryUserService();
        RoleService roleService = new InMemoryRoleService();
        MenuService menuService = new InMemoryMenuService();
        AuthService authService = new InMemoryAuthService(userService, roleService, menuService);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SystemController(authService, userService, roleService, menuService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
                .build();
    }

    @Test
    void shouldReturnCurrentUserMenus() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "token")
        );

        mockMvc.perform(get("/api/system/menus/current-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("dashboard"))
                .andExpect(jsonPath("$.data[1].name").value("system"))
                .andExpect(jsonPath("$.data[1].children[0].name").value("systemUsers"));
    }

    @Test
    void shouldCreateUserWithUnifiedResponse() throws Exception {
        String requestBody = """
                {
                  "username": "editor",
                  "displayName": "内容编辑",
                  "password": "editor123",
                  "enabled": true,
                  "roles": ["USER"]
                }
                """;

        mockMvc.perform(post("/api/system/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("editor"))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));
    }
}
