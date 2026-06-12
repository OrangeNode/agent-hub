package com.agenthub.server.sandbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenthub.sandbox.admin.dto.SandboxControlRequest;
import com.agenthub.sandbox.admin.dto.SandboxControlResult;
import com.agenthub.sandbox.admin.dto.SandboxOverview;
import com.agenthub.sandbox.admin.service.SandboxAdminService;
import com.agenthub.security.jwt.JwtProperties;
import com.agenthub.server.support.ServerWebTestApplication;
import com.agenthub.system.auth.service.AuthService;
import com.agenthub.system.menu.service.MenuService;
import com.agenthub.system.role.service.RoleService;
import com.agenthub.system.user.dto.UserAccount;
import com.agenthub.system.user.dto.UserProfile;
import com.agenthub.system.user.service.UserService;
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
import org.springframework.context.annotation.Import;
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
@Import(SandboxAdminController.class)
class SandboxAdminControllerTest {

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

    @MockitoBean
    private SandboxAdminService sandboxAdminService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void shouldReturnOverviewWhenUserHasMonitorPermission() throws Exception {
        String token = loginUser(1L, "admin", List.of("sandbox:monitor:view"));
        when(sandboxAdminService.overview()).thenReturn(new SandboxOverview(
                true,
                1,
                2,
                0,
                0,
                3,
                4,
                1024L,
                1,
                List.of(),
                List.of()
        ));

        mockMvc.perform(get("/api/sandbox/admin/overview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.activeSessions").value(1))
                .andExpect(jsonPath("$.data.firecrackerProcesses").value(3));
    }

    @Test
    void shouldPassLimitAndOffsetToSandboxListEndpoints() throws Exception {
        String token = loginUser(1L, "admin", List.of("sandbox:monitor:view"));
        when(sandboxAdminService.sessions(null, null, null, 5, 10)).thenReturn(List.of());
        when(sandboxAdminService.executions(null, null, null, 5, 10)).thenReturn(List.of());
        when(sandboxAdminService.artifacts(null, null, 5, 10)).thenReturn(List.of());

        mockMvc.perform(get("/api/sandbox/admin/sessions?limit=5&offset=10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/sandbox/admin/executions?limit=5&offset=10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/sandbox/admin/artifacts?limit=5&offset=10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(sandboxAdminService).sessions(null, null, null, 5, 10);
        verify(sandboxAdminService).executions(null, null, null, 5, 10);
        verify(sandboxAdminService).artifacts(null, null, 5, 10);
    }

    @Test
    void shouldCloseMissingRuntimeSessionsWhenUserHasClosePermission() throws Exception {
        String token = loginUser(1L, "admin", List.of("sandbox:monitor:view", "sandbox:session:close"));
        when(sandboxAdminService.closeMissingRuntimeSessions())
                .thenReturn(new SandboxControlResult(true, List.of("已关闭 2 条缺失运行态的数据库会话"), List.of()));

        mockMvc.perform(post("/api/sandbox/admin/sessions/close-missing-runtime")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        verify(sandboxAdminService).closeMissingRuntimeSessions();
    }

    @Test
    void shouldRejectKillVmWhenUserLacksKillPermission() throws Exception {
        String token = loginUser(2L, "ops", List.of("sandbox:monitor:view"));

        mockMvc.perform(post("/api/sandbox/admin/vms/vm-1/kill")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"释放资源\",\"confirmText\":\"vm-1\",\"dryRun\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void shouldKillVmWhenUserHasKillPermission() throws Exception {
        String token = loginUser(1L, "admin", List.of("sandbox:monitor:view", "sandbox:runtime:kill"));
        when(sandboxAdminService.killVm(eq("vm-1"), any(SandboxControlRequest.class)))
                .thenReturn(new SandboxControlResult(true, List.of("vm-1"), List.of()));

        mockMvc.perform(post("/api/sandbox/admin/vms/vm-1/kill")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"释放资源\",\"confirmText\":\"vm-1\",\"dryRun\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        verify(sandboxAdminService).killVm(eq("vm-1"), any(SandboxControlRequest.class));
    }

    private String loginUser(Long userId, String username, List<String> permissions) {
        UserAccount account = new UserAccount(userId, username, "运维用户", "ignored", true, List.of("ADMIN"));
        UserProfile profile = new UserProfile(
                userId,
                username,
                "运维用户",
                true,
                List.of("ADMIN"),
                permissions
        );
        when(userService.findAccountById(userId)).thenReturn(Optional.of(account));
        when(authService.currentUser(username)).thenReturn(profile);
        return createToken(userId, username, List.of("ADMIN"));
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
