package com.agenthub.server.config;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.security.jwt.JwtProperties;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.server.support.ServerWebTestApplication;
import com.agenthub.system.auth.AuthService;
import com.agenthub.system.menu.MenuService;
import com.agenthub.system.role.RoleService;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = {ServerWebTestApplication.class, SecurityConfigIntegrationTest.TestProbeConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                        + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
        }
)
class SecurityConfigIntegrationTest {

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
    void shouldRebuildAuthoritiesFromServiceSideProfile() throws Exception {
        UserAccount account = new UserAccount(1L, "admin", "平台管理员", "ignored", true, List.of("TOKEN_ROLE"));
        UserProfile profile = new UserProfile(
                1L,
                "admin",
                "平台管理员",
                true,
                List.of("ADMIN"),
                List.of("system:user:list")
        );
        String token = createToken(1L, "admin", List.of("TOKEN_ROLE"));

        when(userService.findAccountById(1L)).thenReturn(Optional.of(account));
        when(authService.currentUser("admin")).thenReturn(profile);

        mockMvc.perform(get("/test/security/authorities")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.authorities[1]").value("system:user:list"));
    }

    @Test
    void shouldReturnUnauthorizedWhenUserIsDeletedAfterTokenIssued() throws Exception {
        String token = createToken(1L, "admin", List.of("ADMIN"));

        when(userService.findAccountById(1L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/test/security/authorities")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.code()))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(authService, never()).currentUser("admin");
    }

    @Test
    void shouldReturnForbiddenWhenUserIsDisabledAfterTokenIssued() throws Exception {
        UserAccount account = new UserAccount(1L, "admin", "平台管理员", "ignored", false, List.of("ADMIN"));
        String token = createToken(1L, "admin", List.of("ADMIN"));

        when(userService.findAccountById(1L)).thenReturn(Optional.of(account));
        when(authService.currentUser("admin")).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(get("/test/security/authorities")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @RestController
    static class AuthorityProbeController {

        @GetMapping("/test/security/authorities")
        AuthoritySnapshot current(Authentication authentication) {
            LoginUser loginUser = (LoginUser) authentication.getPrincipal();
            return new AuthoritySnapshot(
                    loginUser.username(),
                    loginUser.roles(),
                    authentication.getAuthorities().stream().map(authority -> authority.getAuthority()).toList()
            );
        }
    }

    record AuthoritySnapshot(String username, List<String> roles, List<String> authorities) {
    }

    @TestConfiguration
    static class TestProbeConfiguration {

        @Bean
        AuthorityProbeController authorityProbeController() {
            return new AuthorityProbeController();
        }
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
