package com.agenthub.server.system;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenthub.security.jwt.JwtProperties;
import com.agenthub.server.support.ServerWebTestApplication;
import com.agenthub.system.auth.AuthService;
import com.agenthub.system.menu.MenuItem;
import com.agenthub.system.menu.MenuService;
import com.agenthub.system.role.RoleService;
import com.agenthub.system.user.CreateUserCommand;
import com.agenthub.system.user.UserAccount;
import com.agenthub.system.user.UserProfile;
import com.agenthub.system.user.UserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
        classes = ServerWebTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                        + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
        }
)
class SystemControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private JwtProperties jwtProperties;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private RoleService roleService;

    @MockitoBean
    private MenuService menuService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void shouldReturnCurrentUserMenusAfterServerSideProfileReload() throws Exception {
        UserAccount account = new UserAccount(1L, "admin", "平台管理员", "ignored", true, List.of("TOKEN_ROLE"));
        UserProfile profile = new UserProfile(
                1L,
                "admin",
                "平台管理员",
                true,
                List.of("ADMIN"),
                List.of("system:user:list")
        );
        List<MenuItem> menus = List.of(
                new MenuItem(1L, 0L, "dashboard", "工作台", "/dashboard", null, null, null, "MENU", true, 1, List.of()),
                new MenuItem(2L, 0L, "system", "系统管理", "/system", null, null, null, "MENU", true, 2, List.of(
                        new MenuItem(3L, 2L, "systemUsers", "用户管理", "/system/users", null, null, "system:user:list", "MENU", true, 1, List.of())
                ))
        );
        String token = createToken(1L, "admin", List.of("TOKEN_ROLE"));

        when(userService.findAccountById(1L)).thenReturn(Optional.of(account));
        when(authService.currentUser("admin")).thenReturn(profile);
        when(menuService.listCurrentUserMenus(List.of("ADMIN"))).thenReturn(menus);

        mockMvc.perform(get("/api/system/menus/current-user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("dashboard"))
                .andExpect(jsonPath("$.data[1].name").value("system"))
                .andExpect(jsonPath("$.data[1].children[0].name").value("systemUsers"));

        verify(userService).findAccountById(1L);
        verify(authService, times(2)).currentUser("admin");
        verify(menuService).listCurrentUserMenus(List.of("ADMIN"));
    }

    @Test
    void shouldCreateUserWithUnifiedResponseThroughProtectedEndpoint() throws Exception {
        UserAccount account = new UserAccount(1L, "admin", "平台管理员", "ignored", true, List.of("ADMIN"));
        UserProfile currentUser = new UserProfile(
                1L,
                "admin",
                "平台管理员",
                true,
                List.of("ADMIN"),
                List.of("system:user:create")
        );
        UserProfile createdUser = new UserProfile(
                2L,
                "editor",
                "内容编辑",
                true,
                List.of("USER"),
                List.of()
        );
        String token = createToken(1L, "admin", List.of("ADMIN"));
        String requestBody = """
                {
                  "username": "editor",
                  "displayName": "内容编辑",
                  "password": "editor123",
                  "enabled": true,
                  "roles": ["USER"]
                }
                """;

        when(userService.findAccountById(1L)).thenReturn(Optional.of(account));
        when(authService.currentUser("admin")).thenReturn(currentUser);
        when(userService.create(any(CreateUserCommand.class))).thenReturn(createdUser);

        mockMvc.perform(post("/api/system/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("editor"))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));

        verify(userService).findAccountById(1L);
        verify(authService).currentUser("admin");
        verify(userService).create(eq(new CreateUserCommand(
                "editor",
                "内容编辑",
                "editor123",
                true,
                List.of("USER")
        )));
    }

    private String createToken(Long userId, String username, List<String> roles) {
        SecretKey secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("username", username)
                .claim("roles", roles)
                .signWith(secretKey)
                .compact();
    }
}
