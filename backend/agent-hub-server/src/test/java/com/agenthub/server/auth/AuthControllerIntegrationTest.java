package com.agenthub.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenthub.security.jwt.JwtProperties;
import com.agenthub.security.jwt.JwtService;
import com.agenthub.server.support.ServerWebTestApplication;
import com.agenthub.system.auth.dto.LoginResult;
import com.agenthub.system.auth.service.AuthService;
import com.agenthub.system.menu.service.MenuService;
import com.agenthub.system.role.service.RoleService;
import com.agenthub.system.user.dto.UserProfile;
import com.agenthub.system.user.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
class AuthControllerIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private JwtService jwtService;

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
    void shouldLoginAndReturnTokenWithProfilePermissions() throws Exception {
        UserProfile profile = new UserProfile(
                1L,
                "admin",
                "平台管理员",
                true,
                List.of("ADMIN"),
                List.of("system:user:list", "system:user:create")
        );
        String requestBody = """
                {
                  "username": "admin",
                  "password": "admin123"
                }
                """;

        when(authService.login(any())).thenReturn(new LoginResult(profile));

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.profile.username").value("admin"))
                .andExpect(jsonPath("$.data.profile.permissions[0]").value("system:user:list"))
                .andExpect(jsonPath("$.data.profile.permissions[1]").value("system:user:create"))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        String token = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(json)
                .path("data")
                .path("token")
                .asText();

        Claims claims = parseClaims(token);

        assertThat(claims.get("userId", Integer.class)).isEqualTo(1);
        assertThat(claims.get("username", String.class)).isEqualTo("admin");
        assertThat(claims.get("roles", List.class)).containsExactly("ADMIN");
        assertThat(claims.get("permissions")).isNull();
    }

    private Claims parseClaims(String token) {
        SecretKey secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
