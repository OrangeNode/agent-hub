package com.agenthub.server.system;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenthub.security.jwt.JwtProperties;
import com.agenthub.server.support.ServerWebTestApplication;
import com.agenthub.system.auth.service.AuthService;
import com.agenthub.system.menu.service.MenuService;
import com.agenthub.system.operationlog.command.OperationLogQuery;
import com.agenthub.system.operationlog.controller.OperationLogController;
import com.agenthub.system.operationlog.dto.OperationLogItem;
import com.agenthub.system.operationlog.service.OperationLogService;
import com.agenthub.system.role.service.RoleService;
import com.agenthub.system.user.dto.UserAccount;
import com.agenthub.system.user.dto.UserProfile;
import com.agenthub.system.user.service.UserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
@Import(OperationLogController.class)
class OperationLogControllerTest {

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
    private OperationLogService operationLogService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void shouldListOperationLogsWhenCurrentUserHasPermission() throws Exception {
        UserAccount account = new UserAccount(1L, "admin", "平台管理员", "ignored", true, List.of("ADMIN"));
        UserProfile profile = new UserProfile(
                1L,
                "admin",
                "平台管理员",
                true,
                List.of("ADMIN"),
                List.of("operation-log:view")
        );
        OperationLogItem item = new OperationLogItem(
                9L,
                "trace-1",
                1L,
                "admin",
                "Sandbox运维",
                "强杀VM",
                "VM",
                "vm-1",
                "MANAGE",
                "释放资源",
                "POST",
                "/api/sandbox/admin/vms/vm-1/kill",
                "10.0.0.8",
                "JUnit",
                "{\"vmId\":\"vm-1\"}",
                "{\"success\":true}",
                "SUCCESS",
                null,
                null,
                12L,
                OffsetDateTime.parse("2026-06-11T10:15:30+08:00")
        );
        String token = createToken(1L, "admin", List.of("ADMIN"));

        when(userService.findAccountById(1L)).thenReturn(Optional.of(account));
        when(authService.currentUser("admin")).thenReturn(profile);
        when(operationLogService.list(eq(new OperationLogQuery(
                null,
                null,
                "admin",
                null,
                null,
                "SUCCESS",
                null,
                null,
                50
        )))).thenReturn(List.of(item));

        mockMvc.perform(get("/api/system/operation-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("username", "admin")
                        .param("status", "SUCCESS")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].module").value("Sandbox运维"))
                .andExpect(jsonPath("$.data[0].action").value("强杀VM"))
                .andExpect(jsonPath("$.data[0].targetId").value("vm-1"));

        verify(operationLogService).list(eq(new OperationLogQuery(
                null,
                null,
                "admin",
                null,
                null,
                "SUCCESS",
                null,
                null,
                50
        )));
    }

    @Test
    void shouldRejectOperationLogQueryWhenCurrentUserDoesNotHavePermission() throws Exception {
        UserAccount account = new UserAccount(2L, "user", "普通用户", "ignored", true, List.of("USER"));
        UserProfile profile = new UserProfile(
                2L,
                "user",
                "普通用户",
                true,
                List.of("USER"),
                List.of("dashboard:view")
        );
        String token = createToken(2L, "user", List.of("USER"));

        when(userService.findAccountById(2L)).thenReturn(Optional.of(account));
        when(authService.currentUser("user")).thenReturn(profile);

        mockMvc.perform(get("/api/system/operation-logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verify(operationLogService, never()).list(eq(new OperationLogQuery(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
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
