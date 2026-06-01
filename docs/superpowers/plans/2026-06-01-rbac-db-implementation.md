# RBAC 数据库化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前基于内存的 RBAC 实现切换为 `PostgreSQL + MyBatis-Plus + Flyway + BCrypt`，并将 JWT 收缩为仅包含角色信息。

**Architecture:** 保持 `UserService`、`RoleService`、`MenuService`、`AuthService` 接口不变，在 `agent-hub-system` 中新增数据库版实现与持久化对象，通过 `agent-hub-server` 装配到运行链路。JWT 不再携带 `permissions`，由服务端在认证过滤器中动态装配权限。

**Tech Stack:** Spring Boot 4、Spring Security、MyBatis-Plus、Flyway、PostgreSQL、Redis、JUnit 5、MockMvc

---

### Task 1: 接入持久层基础设施与 RBAC 迁移脚本

**Files:**
- Modify: `backend/agent-hub-server/pom.xml`
- Modify: `backend/agent-hub-system/pom.xml`
- Modify: `backend/agent-hub-server/src/main/resources/application.yml`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V1__init_rbac.sql`
- Create: `backend/agent-hub-server/src/test/java/com/agenthub/server/system/RbacMigrationSmokeTest.java`

- [ ] **Step 1: 为数据库化改造写一组会失败的基础校验测试**

```java
package com.agenthub.server.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RbacMigrationSmokeTest {

    @Test
    void shouldContainFlywayMigrationScript() {
        Path path = Path.of("src/main/resources/db/migration/V1__init_rbac.sql");
        assertThat(Files.exists(path)).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试确认当前失败**

Run: `mvn -pl agent-hub-server -Dtest=RbacMigrationSmokeTest test`
Expected: FAIL with `No tests matching pattern` or `src/main/resources/db/migration/V1__init_rbac.sql` not found

- [ ] **Step 3: 添加 MyBatis-Plus、Flyway 与测试依赖**

```xml
<!-- backend/agent-hub-server/pom.xml -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
    <version>3.5.11</version>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>

<!-- backend/agent-hub-system/pom.xml -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-core</artifactId>
    <version>3.5.11</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

```yaml
# backend/agent-hub-server/src/main/resources/application.yml
spring:
  application:
    name: agent-hub-server
  datasource:
    url: jdbc:postgresql://localhost:5432/agent_hub
    username: postgres
    password: postgres
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: localhost
      port: 6379
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
```

- [ ] **Step 4: 编写首个 RBAC 建表与种子数据脚本**

```sql
create table if not exists sys_user (
    id bigint primary key,
    username varchar(64) not null unique,
    display_name varchar(64) not null,
    password varchar(255) not null,
    enabled boolean not null,
    created_by bigint,
    created_at timestamp not null default current_timestamp,
    updated_by bigint,
    updated_at timestamp not null default current_timestamp
);

create table if not exists sys_role (
    id bigint primary key,
    code varchar(64) not null unique,
    name varchar(64) not null,
    enabled boolean not null,
    created_by bigint,
    created_at timestamp not null default current_timestamp,
    updated_by bigint,
    updated_at timestamp not null default current_timestamp
);

create table if not exists sys_menu (
    id bigint primary key,
    parent_id bigint not null,
    name varchar(64) not null,
    title varchar(64) not null,
    path varchar(128),
    component varchar(128),
    icon varchar(64),
    permission varchar(128),
    menu_type varchar(32) not null,
    visible boolean not null,
    sort integer not null,
    created_by bigint,
    created_at timestamp not null default current_timestamp,
    updated_by bigint,
    updated_at timestamp not null default current_timestamp
);

create table if not exists sys_user_role (
    user_id bigint not null,
    role_id bigint not null,
    primary key (user_id, role_id)
);

create table if not exists sys_role_menu (
    role_id bigint not null,
    menu_id bigint not null,
    primary key (role_id, menu_id)
);

insert into sys_role (id, code, name, enabled) values
    (1, 'ADMIN', '管理员', true),
    (2, 'USER', '普通用户', true);

insert into sys_user (id, username, display_name, password, enabled) values
    (1, 'admin', '系统管理员', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', true),
    (2, 'user', '普通用户', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', true);
```

- [ ] **Step 5: 运行测试确认基础设施已落位**

Run: `mvn -pl agent-hub-server -Dtest=RbacMigrationSmokeTest test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/agent-hub-server/pom.xml backend/agent-hub-system/pom.xml backend/agent-hub-server/src/main/resources/application.yml backend/agent-hub-server/src/main/resources/db/migration/V1__init_rbac.sql backend/agent-hub-server/src/test/java/com/agenthub/server/system/RbacMigrationSmokeTest.java
git commit -m "build: add rbac persistence dependencies and schema"
```

### Task 2: 新增 RBAC 实体、Mapper 与数据库版用户/角色/菜单服务

**Files:**
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/user/persistence/SysUser.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/user/persistence/SysUserRole.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/user/persistence/UserMapper.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/role/persistence/SysRole.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/role/persistence/SysRoleMenu.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/role/persistence/RoleMapper.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/menu/persistence/SysMenu.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/menu/persistence/MenuMapper.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/user/DbUserService.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/role/DbRoleService.java`
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/menu/DbMenuService.java`
- Create: `backend/agent-hub-system/src/test/java/com/agenthub/system/db/DbUserRoleMenuServiceTest.java`

- [ ] **Step 1: 写数据库版服务测试，先锁定用户、角色、菜单聚合行为**

```java
package com.agenthub.system.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.system.menu.MenuService;
import com.agenthub.system.role.RoleService;
import com.agenthub.system.user.UserService;
import org.junit.jupiter.api.Test;

class DbUserRoleMenuServiceTest {

    @Test
    void shouldLoadAdminRolesAndMenusFromDatabase() {
        UserService userService = null;
        RoleService roleService = null;
        MenuService menuService = null;

        assertThat(userService.findAccountByUsername("admin")).isPresent();
        assertThat(roleService.findByCodes(java.util.List.of("ADMIN"))).hasSize(1);
        assertThat(menuService.listCurrentUserMenus(java.util.List.of("ADMIN"))).isNotEmpty();
    }
}
```

- [ ] **Step 2: 运行测试确认当前失败**

Run: `mvn -pl agent-hub-system -Dtest=DbUserRoleMenuServiceTest test`
Expected: FAIL with `NullPointerException` or missing database-backed implementation

- [ ] **Step 3: 新增实体与 Mapper，简单查询走 BaseMapper，聚合查询保留显式方法**

```java
@TableName("sys_user")
public class SysUser {
    @TableId
    private Long id;
    private String username;
    private String displayName;
    private String password;
    private Boolean enabled;
}

@Mapper
public interface UserMapper extends BaseMapper<SysUser> {

    SysUser selectByUsername(@Param("username") String username);

    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
```

```java
@Mapper
public interface MenuMapper extends BaseMapper<SysMenu> {

    List<SysMenu> selectByRoleCodes(@Param("roleCodes") List<String> roleCodes);

    List<SysMenu> selectByMenuIds(@Param("menuIds") List<Long> menuIds);
}
```

- [ ] **Step 4: 实现数据库版用户、角色、菜单服务，保持现有接口不变**

```java
public class DbUserService implements UserService {

    private final UserMapper userMapper;

    @Override
    public Optional<UserAccount> findAccountByUsername(String username) {
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) {
            return Optional.empty();
        }
        List<String> roles = userMapper.selectRoleCodesByUserId(user.getId());
        return Optional.of(new UserAccount(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getPassword(),
                Boolean.TRUE.equals(user.getEnabled()),
                roles
        ));
    }
}
```

```java
public class DbMenuService implements MenuService {

    @Override
    public List<MenuItem> listCurrentUserMenus(List<String> roleCodes) {
        List<MenuItem> flatMenus = menuMapper.selectByRoleCodes(roleCodes).stream()
                .map(this::toItem)
                .toList();
        return buildTree(flatMenus);
    }
}
```

- [ ] **Step 5: 运行测试确认数据库版服务通过**

Run: `mvn -pl agent-hub-system -Dtest=DbUserRoleMenuServiceTest test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/agent-hub-system/src/main/java/com/agenthub/system/user/persistence backend/agent-hub-system/src/main/java/com/agenthub/system/role/persistence backend/agent-hub-system/src/main/java/com/agenthub/system/menu/persistence backend/agent-hub-system/src/main/java/com/agenthub/system/user/DbUserService.java backend/agent-hub-system/src/main/java/com/agenthub/system/role/DbRoleService.java backend/agent-hub-system/src/main/java/com/agenthub/system/menu/DbMenuService.java backend/agent-hub-system/src/test/java/com/agenthub/system/db/DbUserRoleMenuServiceTest.java
git commit -m "feat: add database-backed rbac services"
```

### Task 3: 替换认证实现并收缩 JWT

**Files:**
- Create: `backend/agent-hub-system/src/main/java/com/agenthub/system/auth/DbAuthService.java`
- Modify: `backend/agent-hub-security/src/main/java/com/agenthub/security/principal/LoginUser.java`
- Modify: `backend/agent-hub-security/src/main/java/com/agenthub/security/jwt/JwtService.java`
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/auth/AuthController.java`
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/config/SecurityConfig.java`
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/config/AppBeans.java`
- Create: `backend/agent-hub-system/src/test/java/com/agenthub/system/auth/DbAuthServiceTest.java`
- Modify: `backend/agent-hub-security/src/test/java/com/agenthub/security/jwt/JwtServiceTest.java`

- [ ] **Step 1: 写失败测试，锁定 BCrypt 登录和 JWT 仅存角色**

```java
class DbAuthServiceTest {

    @Test
    void shouldAuthenticateWithBcryptAndCollectPermissionsFromDatabase() {
        AuthService authService = null;
        LoginResult result = authService.login(new LoginCommand("admin", "password"));

        assertThat(result.profile().roles()).contains("ADMIN");
        assertThat(result.profile().permissions()).contains("system:user:list");
    }
}
```

```java
class JwtServiceTest {

    @Test
    void shouldCreateTokenWithoutPermissionsClaim() {
        JwtService jwtService = new JwtService(new JwtProperties("agent-hub-demo-secret-agent-hub-demo-secret", Duration.ofHours(12)));
        String token = jwtService.createToken(new LoginUser(1L, "admin", List.of("ADMIN")));

        Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor("agent-hub-demo-secret-agent-hub-demo-secret".getBytes(StandardCharsets.UTF_8))).build().parseSignedClaims(token).getPayload();

        assertThat(claims.get("roles")).isEqualTo(List.of("ADMIN"));
        assertThat(claims.get("permissions")).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认当前失败**

Run: `mvn -pl agent-hub-system,agent-hub-security -Dtest=DbAuthServiceTest,JwtServiceTest test`
Expected: FAIL because `LoginUser` still requires permissions and `DbAuthService` does not exist

- [ ] **Step 3: 实现数据库版认证服务与 JWT 收缩**

```java
public record LoginUser(Long userId, String username, List<String> roles) {
}
```

```java
public class JwtService {

    public String createToken(LoginUser loginUser) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(loginUser.username())
                .claim("userId", loginUser.userId())
                .claim("username", loginUser.username())
                .claim("roles", loginUser.roles())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.ttl())))
                .signWith(secretKey)
                .compact();
    }

    public LoginUser parseToken(String token) {
        Claims claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
        return new LoginUser(
                claims.get("userId", Long.class),
                claims.get("username", String.class),
                castStringList(claims.get("roles"))
        );
    }
}
```

```java
public class DbAuthService implements AuthService {

    private final UserService userService;
    private final RoleService roleService;
    private final MenuService menuService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public LoginResult login(LoginCommand command) {
        UserAccount userAccount = userService.findAccountByUsername(command.username())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误"));
        if (!passwordEncoder.matches(command.password(), userAccount.password())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名或密码错误");
        }
        if (!userAccount.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户已被禁用");
        }
        return new LoginResult(buildProfile(userAccount));
    }
}
```

- [ ] **Step 4: 调整控制器和安全配置，改为服务端动态装配权限**

```java
// AuthController.java
String token = jwtService.createToken(new LoginUser(
        profile.userId(),
        profile.username(),
        profile.roles()
));
```

```java
// SecurityConfig.java
UserProfile profile = authService.currentUser(loginUser.username());
Collection<? extends GrantedAuthority> authorities = Stream.concat(
                loginUser.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)),
                profile.permissions().stream().map(SimpleGrantedAuthority::new)
        )
        .toList();
```

```java
// AppBeans.java
@Bean
PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

- [ ] **Step 5: 运行测试确认认证与 JWT 行为正确**

Run: `mvn -pl agent-hub-system,agent-hub-security -Dtest=DbAuthServiceTest,JwtServiceTest test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/agent-hub-system/src/main/java/com/agenthub/system/auth/DbAuthService.java backend/agent-hub-security/src/main/java/com/agenthub/security/principal/LoginUser.java backend/agent-hub-security/src/main/java/com/agenthub/security/jwt/JwtService.java backend/agent-hub-server/src/main/java/com/agenthub/server/auth/AuthController.java backend/agent-hub-server/src/main/java/com/agenthub/server/config/SecurityConfig.java backend/agent-hub-server/src/main/java/com/agenthub/server/config/AppBeans.java backend/agent-hub-system/src/test/java/com/agenthub/system/auth/DbAuthServiceTest.java backend/agent-hub-security/src/test/java/com/agenthub/security/jwt/JwtServiceTest.java
git commit -m "feat: switch auth and jwt to database-backed roles"
```

### Task 4: 切换系统控制器到数据库实现并补端到端回归测试

**Files:**
- Modify: `backend/agent-hub-server/src/test/java/com/agenthub/server/system/SystemControllerTest.java`
- Create: `backend/agent-hub-server/src/test/java/com/agenthub/server/auth/AuthControllerIntegrationTest.java`
- Create: `backend/agent-hub-server/src/test/java/com/agenthub/server/config/SecurityConfigIntegrationTest.java`
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/config/AppBeans.java`

- [ ] **Step 1: 写失败的集成测试，锁定登录和受保护接口访问行为**

```java
package com.agenthub.server.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldLoginAndReturnProfileWithPermissions() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.profile.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.data.profile.permissions[0]").isNotEmpty());
    }
}
```

```java
package com.agenthub.server.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAuthorizeProtectedApiWithRoleBasedJwtAndServerSidePermissions() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "password"
                                }
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = JsonPath.read(response, "$.data.token");

        mockMvc.perform(get("/api/system/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 运行测试确认当前失败**

Run: `mvn -pl agent-hub-server -Dtest=AuthControllerIntegrationTest,SecurityConfigIntegrationTest,SystemControllerTest test`
Expected: FAIL because tests and database-backed beans are not yet wired end-to-end

- [ ] **Step 3: 更新控制器测试装配，统一走数据库版 Bean 与 Flyway 初始化数据**

```java
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldLoginAndReturnProfileWithPermissions() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.profile.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.data.profile.permissions[*]").value(hasItem("system:user:list")));
    }
}
```

```java
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAuthorizeProtectedApiWithRoleBasedJwtAndServerSidePermissions() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "password"
                                }
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = JsonPath.read(response, "$.data.token");

        mockMvc.perform(get("/api/system/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 4: 运行服务器模块测试，确认数据库版 RBAC 主链路可用**

Run: `mvn -pl agent-hub-server test`
Expected: PASS

- [ ] **Step 5: 运行全量后端测试，确认没有回归**

Run: `mvn test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/agent-hub-server/src/test/java/com/agenthub/server/system/SystemControllerTest.java backend/agent-hub-server/src/test/java/com/agenthub/server/auth/AuthControllerIntegrationTest.java backend/agent-hub-server/src/test/java/com/agenthub/server/config/SecurityConfigIntegrationTest.java backend/agent-hub-server/src/main/java/com/agenthub/server/config/AppBeans.java
git commit -m "test: cover database-backed rbac flow"
```

### Task 5: 清理内存实现依赖并补充交付文档

**Files:**
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/config/AppBeans.java`
- Create: `backend/agent-hub-server/src/test/java/com/agenthub/server/config/AppBeansTest.java`
- Modify: `backend/agent-hub-system/src/test/java/com/agenthub/system/auth/InMemoryAuthServiceTest.java`
- Modify: `backend/agent-hub-system/src/test/java/com/agenthub/system/menu/InMemoryMenuServiceTest.java`
- Modify: `backend/agent-hub-system/src/test/java/com/agenthub/system/role/InMemoryRoleServiceTest.java`
- Modify: `backend/agent-hub-system/src/test/java/com/agenthub/system/user/InMemoryUserServiceTest.java`
- Modify: `README.md`

- [ ] **Step 1: 写失败测试，锁定运行链路不再依赖内存实现**

```java
package com.agenthub.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.system.auth.AuthService;
import com.agenthub.system.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AppBeansTest {

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Test
    void shouldNotWireInMemoryRbacServicesAtRuntime() {
        assertThat(userService.getClass().getSimpleName()).isEqualTo("DbUserService");
        assertThat(authService.getClass().getSimpleName()).isEqualTo("DbAuthService");
    }
}
```

- [ ] **Step 2: 运行测试确认当前失败**

Run: `mvn -pl agent-hub-server -Dtest=AppBeansTest test`
Expected: FAIL because runtime beans still point to `InMemory*Service`

- [ ] **Step 3: 清理运行链路中的内存实现依赖，并更新 README 说明数据库初始化方式**

```java
// AppBeans.java
@Bean
UserService userService(UserMapper userMapper, RoleMapper roleMapper) {
    return new DbUserService(userMapper, roleMapper);
}

@Bean
RoleService roleService(RoleMapper roleMapper, MenuMapper menuMapper) {
    return new DbRoleService(roleMapper, menuMapper);
}

@Bean
MenuService menuService(MenuMapper menuMapper, RoleMapper roleMapper) {
    return new DbMenuService(menuMapper, roleMapper);
}

@Bean
AuthService authService(UserService userService, RoleService roleService, MenuService menuService, PasswordEncoder passwordEncoder) {
    return new DbAuthService(userService, roleService, menuService, passwordEncoder);
}
```

```md
## RBAC 数据初始化

启动 `agent-hub-server` 前，请先准备 PostgreSQL 数据库 `agent_hub`。
应用启动时会自动执行 `Flyway` 脚本初始化 RBAC 表与种子数据。
默认管理员账号为 `admin`，初始化密码为 `password`。
```

- [ ] **Step 4: 运行测试确认运行链路和文档同步完成**

Run: `mvn -pl agent-hub-server -Dtest=AppBeansTest test`
Expected: PASS

- [ ] **Step 5: 运行最终全量测试**

Run: `mvn test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/agent-hub-server/src/main/java/com/agenthub/server/config/AppBeans.java backend/agent-hub-system/src/test/java/com/agenthub/system/auth/InMemoryAuthServiceTest.java backend/agent-hub-system/src/test/java/com/agenthub/system/menu/InMemoryMenuServiceTest.java backend/agent-hub-system/src/test/java/com/agenthub/system/role/InMemoryRoleServiceTest.java backend/agent-hub-system/src/test/java/com/agenthub/system/user/InMemoryUserServiceTest.java README.md
git commit -m "chore: remove runtime dependency on in-memory rbac"
```
