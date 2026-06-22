# Agent-MCP 工具绑定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent 增加统一 Agent-Tool 绑定能力，首版只支持 MCP Tool 级授权，并让聊天运行时只暴露当前 Agent 已绑定且全局启用的 MCP 工具。

**Architecture:** 在 `agent-hub-tool` 增加 `agent_tool_binding` 实体、Mapper、Service 和 Controller，保存统一工具绑定关系。`McpToolProvider` 读取绑定表过滤 `mcp.*` 工具，并在执行侧二次校验授权；`ChatServiceImpl` 把 `agentId` 放入 `ToolExecutionContext`。管理端复用 `AgentsView.vue` 的绑定 Skill 弹窗模式，新增绑定工具弹窗。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis Plus、Flyway、JUnit 5、Mockito、Vue 3、TypeScript、Naive UI、Vitest。

---

## File Structure

新增后端文件：

- `backend/agent-hub-server/src/main/resources/db/migration/V16__init_agent_tool_binding.sql`：创建统一绑定表和 Agent 工具绑定按钮权限。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/entity/AgentToolBinding.java`：`agent_tool_binding` 实体。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/mapper/AgentToolBindingMapper.java`：绑定表 Mapper 和常用查询。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/command/AgentToolBindingCommand.java`：保存绑定命令。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/controller/request/AgentToolBindingRequest.java`：单项绑定请求。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/controller/request/UpdateAgentToolBindingsRequest.java`：批量更新请求。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/dto/AgentToolBindingItem.java`：已绑定工具返回项。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/dto/AgentToolOptionItem.java`：可选工具返回项。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/service/AgentToolBindingService.java`：绑定服务接口。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/service/impl/AgentToolBindingServiceImpl.java`：绑定服务实现。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/controller/AgentToolBindingController.java`：REST 接口。
- `backend/agent-hub-tool/src/test/java/com/agenthub/tool/binding/service/AgentToolBindingServiceImplTest.java`：服务单测。
- `backend/agent-hub-tool/src/test/java/com/agenthub/tool/binding/controller/AgentToolBindingControllerTest.java`：控制器单测。
- `backend/agent-hub-server/src/test/java/com/agenthub/server/agent/AgentToolBindingMigrationSmokeTest.java`：迁移 smoke test。

修改后端文件：

- `backend/agent-hub-tool/pom.xml`：增加对 `agent-hub-agent` 的依赖，用于校验 Agent 存在。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionContext.java`：新增 `agentId` 字段并保留旧构造器兼容测试。
- `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/McpToolProvider.java`：按 Agent 绑定过滤工具和执行授权。
- `backend/agent-hub-tool/src/test/java/com/agenthub/tool/mcp/McpToolProviderTest.java`：调整构造器并新增授权测试。
- `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`：构建 `ToolExecutionContext` 时传入 `agentId`。
- `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`：断言工具上下文携带 `agentId`。

新增或修改管理端文件：

- `admin-web/src/types/agent.ts`：新增 Agent 工具绑定类型。
- `admin-web/src/api/agent.ts`：新增绑定工具 API。
- `admin-web/src/views/AgentsView.vue`：新增绑定工具弹窗和操作。
- `admin-web/tests/agents-tool-binding.spec.ts`：新增静态源码测试。

---

### Task 1: 数据库迁移和绑定实体

**Files:**
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V16__init_agent_tool_binding.sql`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/entity/AgentToolBinding.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/mapper/AgentToolBindingMapper.java`
- Create: `backend/agent-hub-server/src/test/java/com/agenthub/server/agent/AgentToolBindingMigrationSmokeTest.java`

- [ ] **Step 1: Write the failing migration smoke test**

Create `backend/agent-hub-server/src/test/java/com/agenthub/server/agent/AgentToolBindingMigrationSmokeTest.java`:

```java
package com.agenthub.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_tool_binding_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class AgentToolBindingMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AgentToolBindingMapper bindingMapper;

    @Test
    void shouldCreateAgentToolBindingTable() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name = 'agent_tool_binding'
                """, String.class);

        assertThat(tableNames).containsExactly("agent_tool_binding");
    }

    @Test
    void shouldCreateAgentToolBindingConstraintsAndIndexes() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> constraints = jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                    AND table_name = 'agent_tool_binding'
                """, String.class);

        assertThat(constraints).contains(
                "fk_agent_tool_agent",
                "uk_agent_tool_binding"
        );

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT index_name
                FROM information_schema.indexes
                WHERE LOWER(table_schema) = 'public'
                    AND LOWER(table_name) = 'agent_tool_binding'
                """, String.class);

        assertThat(indexes)
                .anyMatch(name -> name.equalsIgnoreCase("idx_agent_tool_agent_sort"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_agent_tool_provider_ref"));
    }

    @Test
    void shouldRegisterAgentToolBindingMapper() {
        assertThat(bindingMapper).isNotNull();
    }

    @Test
    void shouldRegisterAgentToolBindingPermissionForAdmin() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer buttonCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu
                WHERE name = 'agentToolUpdate'
                    AND permission = 'agent:tool:update'
                    AND menu_type = 'BUTTON'
                """, Integer.class);

        assertThat(buttonCount).isEqualTo(1);

        Integer adminPermissionCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu sm ON sm.id = srm.menu_id
                WHERE sr.code = 'ADMIN'
                    AND sm.permission = 'agent:tool:update'
                """, Integer.class);

        assertThat(adminPermissionCount).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the migration smoke test and verify it fails**

Run:

```powershell
mvn -pl agent-hub-server -Dtest=AgentToolBindingMigrationSmokeTest test
```

Expected: FAIL because `AgentToolBindingMapper` and `agent_tool_binding` do not exist.

- [ ] **Step 3: Add the entity**

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/entity/AgentToolBinding.java`:

```java
package com.agenthub.tool.binding.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("agent_tool_binding")
public class AgentToolBinding {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private String toolName;
    private String providerType;
    private Long providerRefId;
    private Boolean enabled;
    private Integer sort;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private Long updatedBy;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public Long getProviderRefId() {
        return providerRefId;
    }

    public void setProviderRefId(Long providerRefId) {
        this.providerRefId = providerRefId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

- [ ] **Step 4: Add the mapper**

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/mapper/AgentToolBindingMapper.java`:

```java
package com.agenthub.tool.binding.mapper;

import com.agenthub.tool.binding.entity.AgentToolBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentToolBindingMapper extends BaseMapper<AgentToolBinding> {

    @Delete("""
            delete from agent_tool_binding
            where agent_id = #{agentId}
                and provider_type = #{providerType}
            """)
    int deleteByAgentIdAndProviderType(
            @Param("agentId") Long agentId,
            @Param("providerType") String providerType
    );

    @Select("""
            select *
            from agent_tool_binding
            where agent_id = #{agentId}
                and provider_type = #{providerType}
            order by sort asc, id asc
            """)
    List<AgentToolBinding> selectByAgentIdAndProviderType(
            @Param("agentId") Long agentId,
            @Param("providerType") String providerType
    );

    @Select("""
            select *
            from agent_tool_binding
            where agent_id = #{agentId}
                and tool_name = #{toolName}
            limit 1
            """)
    AgentToolBinding selectByAgentIdAndToolName(
            @Param("agentId") Long agentId,
            @Param("toolName") String toolName
    );
}
```

- [ ] **Step 5: Add the migration**

Create `backend/agent-hub-server/src/main/resources/db/migration/V16__init_agent_tool_binding.sql`:

```sql
CREATE TABLE agent_tool_binding (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    tool_name VARCHAR(256) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    provider_ref_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_tool_agent FOREIGN KEY (agent_id) REFERENCES agent_config (id) ON DELETE CASCADE,
    CONSTRAINT uk_agent_tool_binding UNIQUE (agent_id, tool_name)
);

CREATE INDEX idx_agent_tool_agent_sort
    ON agent_tool_binding (agent_id, sort, id);

CREATE INDEX idx_agent_tool_provider_ref
    ON agent_tool_binding (provider_type, provider_ref_id);

INSERT INTO sys_menu (parent_id, name, title, path, component, icon, permission, menu_type, visible, sort, created_by, updated_by)
SELECT menu.id, 'agentToolUpdate', '维护 Agent 工具绑定', 'agent:tool:update', NULL, NULL, 'agent:tool:update', 'BUTTON', FALSE, 4, 1, 1
FROM sys_menu menu
WHERE menu.name = 'agentList'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_menu button
        WHERE button.parent_id = menu.id
            AND button.permission = 'agent:tool:update'
    );

INSERT INTO sys_role_menu (role_id, menu_id, created_by, updated_by)
SELECT sr.id, sm.id, 1, 1
FROM sys_role sr
JOIN sys_menu sm ON sm.permission = 'agent:tool:update'
WHERE sr.code = 'ADMIN'
    AND NOT EXISTS (
        SELECT 1
        FROM sys_role_menu srm
        WHERE srm.role_id = sr.id
            AND srm.menu_id = sm.id
    );
```

- [ ] **Step 6: Run the migration smoke test and verify it passes**

Run:

```powershell
mvn -pl agent-hub-server -Dtest=AgentToolBindingMigrationSmokeTest test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 1**

```powershell
git add backend/agent-hub-server/src/main/resources/db/migration/V16__init_agent_tool_binding.sql backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/entity/AgentToolBinding.java backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/mapper/AgentToolBindingMapper.java backend/agent-hub-server/src/test/java/com/agenthub/server/agent/AgentToolBindingMigrationSmokeTest.java
git commit -m "feat: 初始化 Agent 工具绑定表"
```

---

### Task 2: Agent 工具绑定后端 API

**Files:**
- Modify: `backend/agent-hub-tool/pom.xml`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/command/AgentToolBindingCommand.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/controller/request/AgentToolBindingRequest.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/controller/request/UpdateAgentToolBindingsRequest.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/dto/AgentToolBindingItem.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/dto/AgentToolOptionItem.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/service/AgentToolBindingService.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/service/impl/AgentToolBindingServiceImpl.java`
- Create: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/controller/AgentToolBindingController.java`
- Create: `backend/agent-hub-tool/src/test/java/com/agenthub/tool/binding/service/AgentToolBindingServiceImplTest.java`
- Create: `backend/agent-hub-tool/src/test/java/com/agenthub/tool/binding/controller/AgentToolBindingControllerTest.java`

- [ ] **Step 1: Write the failing service test**

Create `backend/agent-hub-tool/src/test/java/com/agenthub/tool/binding/service/AgentToolBindingServiceImplTest.java`:

```java
package com.agenthub.tool.binding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.agent.entity.AgentConfig;
import com.agenthub.agent.mapper.AgentConfigMapper;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.entity.AgentToolBinding;
import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
import com.agenthub.tool.binding.service.impl.AgentToolBindingServiceImpl;
import com.agenthub.tool.mcp.entity.McpServer;
import com.agenthub.tool.mcp.entity.McpTool;
import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentToolBindingServiceImplTest {

    private final AgentToolBindingMapper bindingMapper = mock(AgentToolBindingMapper.class);
    private final AgentConfigMapper agentMapper = mock(AgentConfigMapper.class);
    private final McpToolMapper toolMapper = mock(McpToolMapper.class);
    private final McpServerMapper serverMapper = mock(McpServerMapper.class);
    private final AgentToolBindingService service = new AgentToolBindingServiceImpl(
            bindingMapper,
            agentMapper,
            toolMapper,
            serverMapper
    );

    @Test
    void shouldReplaceMcpBindingsWithDeduplicatedCommands() {
        when(agentMapper.selectById(1L)).thenReturn(agent());
        when(toolMapper.selectById(2L)).thenReturn(tool(2L, 10L, "search_repositories", true));
        when(toolMapper.selectById(3L)).thenReturn(tool(3L, 10L, "get_issue", true));
        when(serverMapper.selectById(10L)).thenReturn(server(10L, "github", true));
        ArgumentCaptor<AgentToolBinding> captor = ArgumentCaptor.forClass(AgentToolBinding.class);
        when(bindingMapper.insert(captor.capture())).thenReturn(1);
        when(bindingMapper.selectByAgentIdAndProviderType(1L, "MCP")).thenReturn(List.of());

        service.updateBindings(7L, 1L, List.of(
                new AgentToolBindingCommand("mcp.github.search_repositories", "MCP", 2L, 5),
                new AgentToolBindingCommand("mcp.github.get_issue", "MCP", 3L, 1),
                new AgentToolBindingCommand("mcp.github.search_repositories", "MCP", 2L, 9)
        ));

        verify(bindingMapper).deleteByAgentIdAndProviderType(1L, "MCP");
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues()).extracting(AgentToolBinding::getToolName)
                .containsExactly("mcp.github.search_repositories", "mcp.github.get_issue");
        assertThat(captor.getAllValues()).extracting(AgentToolBinding::getSort)
                .containsExactly(5, 1);
        assertThat(captor.getAllValues()).extracting(AgentToolBinding::getCreatedBy)
                .containsExactly(7L, 7L);
    }

    @Test
    void shouldRejectUnsupportedProviderType() {
        when(agentMapper.selectById(1L)).thenReturn(agent());

        assertThatThrownBy(() -> service.updateBindings(7L, 1L, List.of(
                new AgentToolBindingCommand("sandbox.exec", "SANDBOX", 1L, 0)
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("暂不支持该工具来源")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectMismatchedToolNameAndMcpTool() {
        when(agentMapper.selectById(1L)).thenReturn(agent());
        when(toolMapper.selectById(2L)).thenReturn(tool(2L, 10L, "search_repositories", true));
        when(serverMapper.selectById(10L)).thenReturn(server(10L, "github", true));

        assertThatThrownBy(() -> service.updateBindings(7L, 1L, List.of(
                new AgentToolBindingCommand("mcp.github.delete_repo", "MCP", 2L, 0)
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("工具标识与 MCP 工具不匹配")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldListBindingsWithServerAndToolStatus() {
        AgentToolBinding binding = binding(1L, 1L, "mcp.github.search_repositories", 2L);
        when(agentMapper.selectById(1L)).thenReturn(agent());
        when(bindingMapper.selectByAgentIdAndProviderType(1L, "MCP")).thenReturn(List.of(binding));
        when(toolMapper.selectById(2L)).thenReturn(tool(2L, 10L, "search_repositories", false));
        when(serverMapper.selectById(10L)).thenReturn(server(10L, "github", true));

        var items = service.listBindings(1L);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().toolName()).isEqualTo("mcp.github.search_repositories");
        assertThat(items.getFirst().serverKey()).isEqualTo("github");
        assertThat(items.getFirst().serverEnabled()).isTrue();
        assertThat(items.getFirst().toolEnabled()).isFalse();
    }

    @Test
    void shouldListMcpOptions() {
        when(agentMapper.selectById(1L)).thenReturn(agent());
        when(serverMapper.selectList(any())).thenReturn(List.of(server(10L, "github", true)));
        when(toolMapper.selectList(any())).thenReturn(List.of(tool(2L, 10L, "search_repositories", true)));

        var options = service.listOptions(1L);

        assertThat(options).hasSize(1);
        assertThat(options.getFirst().toolName()).isEqualTo("mcp.github.search_repositories");
        assertThat(options.getFirst().providerType()).isEqualTo("MCP");
        assertThat(options.getFirst().providerRefId()).isEqualTo(2L);
    }

    private AgentConfig agent() {
        AgentConfig agent = new AgentConfig();
        agent.setId(1L);
        agent.setAgentKey("official-writing");
        agent.setName("公文写作 Agent");
        return agent;
    }

    private AgentToolBinding binding(Long id, Long agentId, String toolName, Long providerRefId) {
        AgentToolBinding binding = new AgentToolBinding();
        binding.setId(id);
        binding.setAgentId(agentId);
        binding.setToolName(toolName);
        binding.setProviderType("MCP");
        binding.setProviderRefId(providerRefId);
        binding.setEnabled(true);
        binding.setSort(0);
        return binding;
    }

    private McpServer server(Long id, String serverKey, boolean enabled) {
        McpServer server = new McpServer();
        server.setId(id);
        server.setServerKey(serverKey);
        server.setName("GitHub");
        server.setEnabled(enabled);
        return server;
    }

    private McpTool tool(Long id, Long serverId, String toolName, boolean enabled) {
        McpTool tool = new McpTool();
        tool.setId(id);
        tool.setServerId(serverId);
        tool.setToolName(toolName);
        tool.setDisplayName("搜索仓库");
        tool.setDescription("搜索 GitHub 仓库");
        tool.setInputSchema("{\"type\":\"object\"}");
        tool.setEnabled(enabled);
        return tool;
    }
}
```

- [ ] **Step 2: Run the service test and verify it fails**

Run:

```powershell
mvn -pl agent-hub-tool -Dtest=AgentToolBindingServiceImplTest test
```

Expected: FAIL because binding command, DTO, service and agent dependency do not exist.

- [ ] **Step 3: Add `agent-hub-agent` dependency**

Modify `backend/agent-hub-tool/pom.xml` by adding this dependency after `agent-hub-ai`:

```xml
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-agent</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 4: Add command, request and DTO records**

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/command/AgentToolBindingCommand.java`:

```java
package com.agenthub.tool.binding.command;

public record AgentToolBindingCommand(
        String toolName,
        String providerType,
        Long providerRefId,
        Integer sort
) {
}
```

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/controller/request/AgentToolBindingRequest.java`:

```java
package com.agenthub.tool.binding.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentToolBindingRequest(
        @NotBlank(message = "工具标识不能为空")
        @Size(max = 256, message = "工具标识不能超过 256 个字符")
        String toolName,

        @NotBlank(message = "工具来源不能为空")
        @Size(max = 32, message = "工具来源不能超过 32 个字符")
        String providerType,

        @NotNull(message = "工具来源记录不能为空")
        Long providerRefId,

        Integer sort
) {
}
```

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/controller/request/UpdateAgentToolBindingsRequest.java`:

```java
package com.agenthub.tool.binding.controller.request;

import jakarta.validation.Valid;
import java.util.List;

public record UpdateAgentToolBindingsRequest(
        List<@Valid AgentToolBindingRequest> tools
) {
}
```

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/dto/AgentToolBindingItem.java`:

```java
package com.agenthub.tool.binding.dto;

public record AgentToolBindingItem(
        Long bindingId,
        Long agentId,
        String toolName,
        String providerType,
        Long providerRefId,
        String serverKey,
        String serverName,
        String toolDisplayName,
        String toolDescription,
        boolean serverEnabled,
        boolean toolEnabled,
        boolean enabled,
        Integer sort
) {
}
```

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/dto/AgentToolOptionItem.java`:

```java
package com.agenthub.tool.binding.dto;

public record AgentToolOptionItem(
        String toolName,
        String providerType,
        Long providerRefId,
        String serverKey,
        String serverName,
        String remoteToolName,
        String exposedToolName,
        String displayName,
        String description,
        boolean serverEnabled,
        boolean toolEnabled
) {
}
```

- [ ] **Step 5: Add the service interface and implementation**

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/service/AgentToolBindingService.java`:

```java
package com.agenthub.tool.binding.service;

import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.dto.AgentToolBindingItem;
import com.agenthub.tool.binding.dto.AgentToolOptionItem;
import java.util.List;

public interface AgentToolBindingService {

    List<AgentToolBindingItem> listBindings(Long agentId);

    List<AgentToolOptionItem> listOptions(Long agentId);

    List<AgentToolBindingItem> updateBindings(Long userId, Long agentId, List<AgentToolBindingCommand> commands);
}
```

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/service/impl/AgentToolBindingServiceImpl.java`:

```java
package com.agenthub.tool.binding.service.impl;

import com.agenthub.agent.entity.AgentConfig;
import com.agenthub.agent.mapper.AgentConfigMapper;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.dto.AgentToolBindingItem;
import com.agenthub.tool.binding.dto.AgentToolOptionItem;
import com.agenthub.tool.binding.entity.AgentToolBinding;
import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
import com.agenthub.tool.binding.service.AgentToolBindingService;
import com.agenthub.tool.mcp.entity.McpServer;
import com.agenthub.tool.mcp.entity.McpTool;
import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentToolBindingServiceImpl implements AgentToolBindingService {

    public static final String PROVIDER_MCP = "MCP";
    private static final String MCP_PREFIX = "mcp.";

    private final AgentToolBindingMapper bindingMapper;
    private final AgentConfigMapper agentMapper;
    private final McpToolMapper toolMapper;
    private final McpServerMapper serverMapper;

    public AgentToolBindingServiceImpl(
            AgentToolBindingMapper bindingMapper,
            AgentConfigMapper agentMapper,
            McpToolMapper toolMapper,
            McpServerMapper serverMapper
    ) {
        this.bindingMapper = bindingMapper;
        this.agentMapper = agentMapper;
        this.toolMapper = toolMapper;
        this.serverMapper = serverMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentToolBindingItem> listBindings(Long agentId) {
        requireAgent(agentId);
        return bindingMapper.selectByAgentIdAndProviderType(agentId, PROVIDER_MCP).stream()
                .map(this::toBindingItem)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentToolOptionItem> listOptions(Long agentId) {
        requireAgent(agentId);
        List<McpServer> servers = serverMapper.selectList(new LambdaQueryWrapper<McpServer>()
                .orderByAsc(McpServer::getSort)
                .orderByAsc(McpServer::getId));
        return servers.stream()
                .flatMap(server -> toolMapper.selectList(new LambdaQueryWrapper<McpTool>()
                                .eq(McpTool::getServerId, server.getId())
                                .orderByDesc(McpTool::getEnabled)
                                .orderByAsc(McpTool::getToolName))
                        .stream()
                        .map(tool -> toOptionItem(server, tool)))
                .toList();
    }

    @Override
    @Transactional
    public List<AgentToolBindingItem> updateBindings(
            Long userId,
            Long agentId,
            List<AgentToolBindingCommand> commands
    ) {
        requireAgent(agentId);
        Map<String, AgentToolBindingCommand> uniqueCommands = new LinkedHashMap<>();
        if (commands != null) {
            for (AgentToolBindingCommand command : commands) {
                if (command == null || !hasText(command.toolName()) || uniqueCommands.containsKey(command.toolName())) {
                    continue;
                }
                validateMcpCommand(command);
                uniqueCommands.put(command.toolName(), command);
            }
        }

        bindingMapper.deleteByAgentIdAndProviderType(agentId, PROVIDER_MCP);
        OffsetDateTime now = OffsetDateTime.now();
        for (AgentToolBindingCommand command : uniqueCommands.values()) {
            AgentToolBinding binding = new AgentToolBinding();
            binding.setAgentId(agentId);
            binding.setToolName(command.toolName());
            binding.setProviderType(PROVIDER_MCP);
            binding.setProviderRefId(command.providerRefId());
            binding.setEnabled(true);
            binding.setSort(command.sort() == null ? 0 : command.sort());
            binding.setCreatedBy(userId);
            binding.setCreatedAt(now);
            binding.setUpdatedBy(userId);
            binding.setUpdatedAt(now);
            bindingMapper.insert(binding);
        }
        return listBindings(agentId);
    }

    private void validateMcpCommand(AgentToolBindingCommand command) {
        if (!PROVIDER_MCP.equals(command.providerType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "暂不支持该工具来源");
        }
        McpTool tool = requireTool(command.providerRefId());
        McpServer server = requireServer(tool.getServerId());
        String expectedToolName = exposedToolName(server.getServerKey(), tool.getToolName());
        if (!expectedToolName.equals(command.toolName())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工具标识与 MCP 工具不匹配");
        }
    }

    private AgentConfig requireAgent(Long agentId) {
        AgentConfig agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        return agent;
    }

    private McpTool requireTool(Long toolId) {
        McpTool tool = toolMapper.selectById(toolId);
        if (tool == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "MCP 工具不存在");
        }
        return tool;
    }

    private McpServer requireServer(Long serverId) {
        McpServer server = serverMapper.selectById(serverId);
        if (server == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "MCP 服务不存在");
        }
        return server;
    }

    private AgentToolBindingItem toBindingItem(AgentToolBinding binding) {
        McpTool tool = toolMapper.selectById(binding.getProviderRefId());
        McpServer server = tool == null ? null : serverMapper.selectById(tool.getServerId());
        return new AgentToolBindingItem(
                binding.getId(),
                binding.getAgentId(),
                binding.getToolName(),
                binding.getProviderType(),
                binding.getProviderRefId(),
                server == null ? null : server.getServerKey(),
                server == null ? null : server.getName(),
                tool == null ? null : tool.getDisplayName(),
                tool == null ? null : tool.getDescription(),
                server != null && Boolean.TRUE.equals(server.getEnabled()),
                tool != null && Boolean.TRUE.equals(tool.getEnabled()),
                Boolean.TRUE.equals(binding.getEnabled()),
                binding.getSort() == null ? 0 : binding.getSort()
        );
    }

    private AgentToolOptionItem toOptionItem(McpServer server, McpTool tool) {
        String exposedToolName = exposedToolName(server.getServerKey(), tool.getToolName());
        return new AgentToolOptionItem(
                exposedToolName,
                PROVIDER_MCP,
                tool.getId(),
                server.getServerKey(),
                server.getName(),
                tool.getToolName(),
                exposedToolName,
                tool.getDisplayName(),
                tool.getDescription(),
                Boolean.TRUE.equals(server.getEnabled()),
                Boolean.TRUE.equals(tool.getEnabled())
        );
    }

    private String exposedToolName(String serverKey, String toolName) {
        return MCP_PREFIX + serverKey + "." + toolName;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 6: Run the service test and verify it passes**

Run:

```powershell
mvn -pl agent-hub-tool -Dtest=AgentToolBindingServiceImplTest test
```

Expected: PASS.

- [ ] **Step 7: Write the failing controller test**

Create `backend/agent-hub-tool/src/test/java/com/agenthub/tool/binding/controller/AgentToolBindingControllerTest.java`:

```java
package com.agenthub.tool.binding.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.controller.request.AgentToolBindingRequest;
import com.agenthub.tool.binding.controller.request.UpdateAgentToolBindingsRequest;
import com.agenthub.tool.binding.dto.AgentToolBindingItem;
import com.agenthub.tool.binding.dto.AgentToolOptionItem;
import com.agenthub.tool.binding.service.AgentToolBindingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class AgentToolBindingControllerTest {

    @Test
    void shouldListBindings() {
        AgentToolBindingService service = mock(AgentToolBindingService.class);
        AgentToolBindingController controller = new AgentToolBindingController(service);
        AgentToolBindingItem item = bindingItem();
        when(service.listBindings(1L)).thenReturn(List.of(item));

        ApiResponse<List<AgentToolBindingItem>> response = controller.listBindings(1L);

        assertThat(response.data()).containsExactly(item);
        verify(service).listBindings(1L);
    }

    @Test
    void shouldListOptions() {
        AgentToolBindingService service = mock(AgentToolBindingService.class);
        AgentToolBindingController controller = new AgentToolBindingController(service);
        AgentToolOptionItem item = optionItem();
        when(service.listOptions(1L)).thenReturn(List.of(item));

        ApiResponse<List<AgentToolOptionItem>> response = controller.listOptions(1L);

        assertThat(response.data()).containsExactly(item);
        verify(service).listOptions(1L);
    }

    @Test
    void shouldUpdateBindingsByMappingRequestToCommands() {
        AgentToolBindingService service = mock(AgentToolBindingService.class);
        AgentToolBindingController controller = new AgentToolBindingController(service);
        ArgumentCaptor<List<AgentToolBindingCommand>> captor = ArgumentCaptor.forClass(List.class);
        AgentToolBindingItem item = bindingItem();
        when(service.updateBindings(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(1L),
                captor.capture())).thenReturn(List.of(item));

        ApiResponse<List<AgentToolBindingItem>> response = controller.updateBindings(
                authentication(),
                1L,
                new UpdateAgentToolBindingsRequest(List.of(
                        new AgentToolBindingRequest("mcp.github.search_repositories", "MCP", 2L, 3)
                ))
        );

        assertThat(response.data()).containsExactly(item);
        assertThat(captor.getValue()).containsExactly(new AgentToolBindingCommand(
                "mcp.github.search_repositories",
                "MCP",
                2L,
                3
        ));
    }

    private Authentication authentication() {
        LoginUser loginUser = new LoginUser(7L, "admin", "管理员", "token", List.of());
        return new UsernamePasswordAuthenticationToken(loginUser, null, List.of());
    }

    private AgentToolBindingItem bindingItem() {
        return new AgentToolBindingItem(
                9L,
                1L,
                "mcp.github.search_repositories",
                "MCP",
                2L,
                "github",
                "GitHub",
                "搜索仓库",
                "搜索 GitHub 仓库",
                true,
                true,
                true,
                0
        );
    }

    private AgentToolOptionItem optionItem() {
        return new AgentToolOptionItem(
                "mcp.github.search_repositories",
                "MCP",
                2L,
                "github",
                "GitHub",
                "search_repositories",
                "mcp.github.search_repositories",
                "搜索仓库",
                "搜索 GitHub 仓库",
                true,
                true
        );
    }
}
```

- [ ] **Step 8: Run the controller test and verify it fails**

Run:

```powershell
mvn -pl agent-hub-tool -Dtest=AgentToolBindingControllerTest test
```

Expected: FAIL because `AgentToolBindingController` does not exist.

- [ ] **Step 9: Add the controller**

Create `backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding/controller/AgentToolBindingController.java`:

```java
package com.agenthub.tool.binding.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.tool.binding.command.AgentToolBindingCommand;
import com.agenthub.tool.binding.controller.request.UpdateAgentToolBindingsRequest;
import com.agenthub.tool.binding.dto.AgentToolBindingItem;
import com.agenthub.tool.binding.dto.AgentToolOptionItem;
import com.agenthub.tool.binding.service.AgentToolBindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/agents/{agentId}/tools")
public class AgentToolBindingController {

    private final AgentToolBindingService bindingService;

    public AgentToolBindingController(AgentToolBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('agent:tool:update')")
    public ApiResponse<List<AgentToolBindingItem>> listBindings(
            @PathVariable @Positive(message = "Agent 必须为正数") Long agentId
    ) {
        return ApiResponse.success(bindingService.listBindings(agentId));
    }

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('agent:tool:update')")
    public ApiResponse<List<AgentToolOptionItem>> listOptions(
            @PathVariable @Positive(message = "Agent 必须为正数") Long agentId
    ) {
        return ApiResponse.success(bindingService.listOptions(agentId));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('agent:tool:update')")
    public ApiResponse<List<AgentToolBindingItem>> updateBindings(
            Authentication authentication,
            @PathVariable @Positive(message = "Agent 必须为正数") Long agentId,
            @Valid @RequestBody UpdateAgentToolBindingsRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        List<AgentToolBindingCommand> commands = request.tools() == null
                ? List.of()
                : request.tools().stream()
                        .map(item -> new AgentToolBindingCommand(
                                item.toolName(),
                                item.providerType(),
                                item.providerRefId(),
                                item.sort()
                        ))
                        .toList();
        return ApiResponse.success(bindingService.updateBindings(loginUser.userId(), agentId, commands));
    }
}
```

- [ ] **Step 10: Run backend binding API tests and verify they pass**

Run:

```powershell
mvn -pl agent-hub-tool -Dtest=AgentToolBindingServiceImplTest,AgentToolBindingControllerTest test
```

Expected: PASS.

- [ ] **Step 11: Commit Task 2**

```powershell
git add backend/agent-hub-tool/pom.xml backend/agent-hub-tool/src/main/java/com/agenthub/tool/binding backend/agent-hub-tool/src/test/java/com/agenthub/tool/binding
git commit -m "feat: 增加 Agent 工具绑定接口"
```

---

### Task 3: MCP 运行时按 Agent 授权过滤

**Files:**
- Modify: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionContext.java`
- Modify: `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/McpToolProvider.java`
- Modify: `backend/agent-hub-tool/src/test/java/com/agenthub/tool/mcp/McpToolProviderTest.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: Write failing tests for MCP authorization**

Modify `backend/agent-hub-tool/src/test/java/com/agenthub/tool/mcp/McpToolProviderTest.java`:

```java
// Add this import:
import com.agenthub.tool.binding.entity.AgentToolBinding;
import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
```

Change the fields and provider construction:

```java
    private final McpServerMapper serverMapper = mock(McpServerMapper.class);
    private final McpToolMapper toolMapper = mock(McpToolMapper.class);
    private final McpToolCallLogMapper callLogMapper = mock(McpToolCallLogMapper.class);
    private final AgentToolBindingMapper bindingMapper = mock(AgentToolBindingMapper.class);
    private final McpClient mcpClient = mock(McpClient.class);
    private final McpToolProvider provider = new McpToolProvider(
            serverMapper,
            toolMapper,
            callLogMapper,
            bindingMapper,
            mcpClient
    );
```

Replace `shouldExposeEnabledMcpToolsWithNamespace` with:

```java
    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeOnlyBoundEnabledMcpToolsWithNamespace() {
        McpServer server = server(true);
        McpTool tool = tool(true);
        when(bindingMapper.selectByAgentIdAndProviderType(1L, "MCP")).thenReturn(List.of(binding()));
        when(toolMapper.selectById(2L)).thenReturn(tool);
        when(serverMapper.selectById(1L)).thenReturn(server);

        List<LlmToolDefinition> tools = provider.listTools(context());

        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().name()).isEqualTo("mcp.github.search_repositories");
        assertThat(tools.getFirst().description()).isEqualTo("搜索 GitHub 仓库");
        assertThat(tools.getFirst().parameters()).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) tools.getFirst().parameters().get("properties");
        assertThat(properties).containsKey("query");
    }
```

Add these tests:

```java
    @Test
    void shouldNotExposeMcpToolsWhenAgentIdMissing() {
        ToolExecutionContext context = new ToolExecutionContext(7L, 100L, 200L, "official-writing",
                null, List.of(), ToolEventSink.noop());

        List<LlmToolDefinition> tools = provider.listTools(context);

        assertThat(tools).isEmpty();
        verifyNoInteractions(bindingMapper);
    }

    @Test
    void shouldNotExecuteUnboundTool() {
        when(bindingMapper.selectByAgentIdAndToolName(1L, "mcp.github.search_repositories")).thenReturn(null);

        ToolExecutionResult result = provider.execute(
                new ToolExecutionRequest("call-1", "mcp.github.search_repositories", Map.of("query", "codex")),
                context()
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("当前 Agent 未授权使用工具: mcp.github.search_repositories");
        verifyNoInteractions(mcpClient);
    }
```

Update existing execute tests to stub the binding before server/tool lookup:

```java
when(bindingMapper.selectByAgentIdAndToolName(1L, "mcp.github.search_repositories")).thenReturn(binding());
```

Replace the existing `context()` helper with:

```java
    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, 100L, 200L, "official-writing", 1L, List.of(), ToolEventSink.noop());
    }

    private AgentToolBinding binding() {
        AgentToolBinding binding = new AgentToolBinding();
        binding.setId(9L);
        binding.setAgentId(1L);
        binding.setToolName("mcp.github.search_repositories");
        binding.setProviderType("MCP");
        binding.setProviderRefId(2L);
        binding.setEnabled(true);
        binding.setSort(0);
        return binding;
    }
```

- [ ] **Step 2: Run MCP provider test and verify it fails**

Run:

```powershell
mvn -pl agent-hub-tool -Dtest=McpToolProviderTest test
```

Expected: FAIL because `ToolExecutionContext.agentId()` and the new `McpToolProvider` constructor do not exist.

- [ ] **Step 3: Update `ToolExecutionContext`**

Replace `backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionContext.java` with:

```java
package com.agenthub.tool.runtime;

import java.util.List;

public record ToolExecutionContext(
        Long userId,
        Long conversationId,
        Long assistantMessageId,
        String agentKey,
        Long agentId,
        List<Long> skillIds,
        ToolEventSink eventSink
) {

    public ToolExecutionContext(
            Long userId,
            Long conversationId,
            Long assistantMessageId,
            String agentKey,
            ToolEventSink eventSink
    ) {
        this(userId, conversationId, assistantMessageId, agentKey, null, List.of(), eventSink);
    }

    public ToolExecutionContext(
            Long userId,
            Long conversationId,
            Long assistantMessageId,
            String agentKey,
            List<Long> skillIds,
            ToolEventSink eventSink
    ) {
        this(userId, conversationId, assistantMessageId, agentKey, null, skillIds, eventSink);
    }

    public ToolExecutionContext {
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        if (eventSink == null) {
            eventSink = ToolEventSink.noop();
        }
    }
}
```

- [ ] **Step 4: Update `McpToolProvider` constructor and authorization**

Modify `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/McpToolProvider.java`:

```java
// Add imports:
import com.agenthub.tool.binding.entity.AgentToolBinding;
import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
```

Add field and constructor parameter:

```java
    private static final String PROVIDER_MCP = "MCP";

    private final McpServerMapper serverMapper;
    private final McpToolMapper toolMapper;
    private final McpToolCallLogMapper callLogMapper;
    private final AgentToolBindingMapper bindingMapper;
    private final McpClient mcpClient;

    public McpToolProvider(
            McpServerMapper serverMapper,
            McpToolMapper toolMapper,
            McpToolCallLogMapper callLogMapper,
            AgentToolBindingMapper bindingMapper,
            McpClient mcpClient
    ) {
        this.serverMapper = serverMapper;
        this.toolMapper = toolMapper;
        this.callLogMapper = callLogMapper;
        this.bindingMapper = bindingMapper;
        this.mcpClient = mcpClient;
    }
```

Replace `listTools` with:

```java
    @Override
    public List<LlmToolDefinition> listTools(ToolExecutionContext context) {
        if (context == null || context.agentId() == null) {
            return List.of();
        }
        List<LlmToolDefinition> definitions = new ArrayList<>();
        List<AgentToolBinding> bindings = bindingMapper.selectByAgentIdAndProviderType(context.agentId(), PROVIDER_MCP);
        for (AgentToolBinding binding : bindings) {
            if (binding == null || !Boolean.TRUE.equals(binding.getEnabled())) {
                continue;
            }
            McpTool tool = toolMapper.selectById(binding.getProviderRefId());
            if (tool == null || !Boolean.TRUE.equals(tool.getEnabled())) {
                continue;
            }
            McpServer server = serverMapper.selectById(tool.getServerId());
            if (server == null || !Boolean.TRUE.equals(server.getEnabled())) {
                continue;
            }
            definitions.add(new LlmToolDefinition(
                    exposedToolName(server.getServerKey(), tool.getToolName()),
                    defaultText(tool.getDescription(), tool.getDisplayName()),
                    parseParameters(tool.getInputSchema())
            ));
        }
        return definitions;
    }
```

At the start of `execute`, after parsing `ParsedToolName parsed`, add:

```java
        if (context == null || context.agentId() == null) {
            return ToolExecutionResult.failed("当前 Agent 未授权使用工具: " + request.toolName());
        }
        AgentToolBinding binding = bindingMapper.selectByAgentIdAndToolName(context.agentId(), request.toolName());
        if (binding == null || !Boolean.TRUE.equals(binding.getEnabled()) || !PROVIDER_MCP.equals(binding.getProviderType())) {
            return ToolExecutionResult.failed("当前 Agent 未授权使用工具: " + request.toolName());
        }
```

- [ ] **Step 5: Run MCP provider test and verify it passes**

Run:

```powershell
mvn -pl agent-hub-tool -Dtest=McpToolProviderTest test
```

Expected: PASS.

- [ ] **Step 6: Update ChatService context to pass agentId**

Modify `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`:

In `stream`, before `streamExecutor.execute(...)`, add:

```java
        Long resolvedAgentId = agentRuntime == null ? null : agentRuntime.agentId();
```

Change the call to `streamLlm` so it passes `resolvedAgentId` after `agentKey`:

```java
            streamExecutor.execute(() -> streamLlm(emitter, preparedChat.conversation().getUserId(),
                    preparedChat.conversation().getId(), runtimeConfig, preparedChat.context().messages(),
                    preparedChat.assistantMessage(), preparedChat.conversation().getAgentKey(), resolvedAgentId,
                    thinkingEnabled, preparedChat.skillContext()));
```

Change `streamLlm` signature:

```java
            String agentKey,
            Long agentId,
            boolean thinkingEnabled,
            SkillRuntimeContext skillContext
```

Change `ToolExecutionContext` creation:

```java
        ToolExecutionContext toolContext = new ToolExecutionContext(
                userId,
                conversationId,
                assistantMessage.getId(),
                agentKey,
                agentId,
                skillIds,
                (toolCallId, toolName, stream, output) -> {
```

- [ ] **Step 7: Update ChatServiceImplTest expectations**

Modify `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`:

Find tests that assert `listContextCaptor.getValue().agentKey()` and `contextCaptor.getValue().agentKey()`. Add assertions:

```java
assertThat(listContextCaptor.getValue().agentId()).isEqualTo(1L);
```

and:

```java
assertThat(contextCaptor.getValue().agentId()).isEqualTo(1L);
```

If a test uses no Agent, assert:

```java
assertThat(listContextCaptor.getValue().agentId()).isNull();
```

- [ ] **Step 8: Run ChatService tool context tests**

Run:

```powershell
mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test
```

Expected: PASS.

- [ ] **Step 9: Commit Task 3**

```powershell
git add backend/agent-hub-tool/src/main/java/com/agenthub/tool/runtime/ToolExecutionContext.java backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/McpToolProvider.java backend/agent-hub-tool/src/test/java/com/agenthub/tool/mcp/McpToolProviderTest.java backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java
git commit -m "feat: 按 Agent 授权暴露 MCP 工具"
```

---

### Task 4: 管理端 Agent 绑定工具弹窗

**Files:**
- Modify: `admin-web/src/types/agent.ts`
- Modify: `admin-web/src/api/agent.ts`
- Modify: `admin-web/src/views/AgentsView.vue`
- Create: `admin-web/tests/agents-tool-binding.spec.ts`

- [ ] **Step 1: Write the failing frontend static test**

Create `admin-web/tests/agents-tool-binding.spec.ts`:

```typescript
import { describe, expect, it } from 'vitest'
import agentApiSource from '../src/api/agent.ts?raw'
import agentTypeSource from '../src/types/agent.ts?raw'
import agentsViewSource from '../src/views/AgentsView.vue?raw'

describe('Agent 工具绑定', () => {
  it('定义 Agent 工具绑定类型', () => {
    expect(agentTypeSource).toContain('export interface AgentToolBindingRecord')
    expect(agentTypeSource).toContain('providerType: string')
    expect(agentTypeSource).toContain('providerRefId: number')
    expect(agentTypeSource).toContain('serverEnabled: boolean')
    expect(agentTypeSource).toContain('toolEnabled: boolean')
    expect(agentTypeSource).toContain('export interface AgentToolOptionRecord')
  })

  it('提供 Agent 工具绑定 API', () => {
    expect(agentApiSource).toContain('fetchAgentToolBindings')
    expect(agentApiSource).toContain('fetchAgentToolOptions')
    expect(agentApiSource).toContain('updateAgentToolBindings')
    expect(agentApiSource).toContain('/agents/${agentId}/tools')
    expect(agentApiSource).toContain('/agents/${agentId}/tools/options')
  })

  it('Agent 页面提供绑定工具操作和弹窗', () => {
    expect(agentsViewSource).toContain('showToolModal')
    expect(agentsViewSource).toContain('toolModalAgent')
    expect(agentsViewSource).toContain('openTools')
    expect(agentsViewSource).toContain('submitToolBindings')
    expect(agentsViewSource).toContain("default: () => '工具'")
    expect(agentsViewSource).toContain('绑定工具')
  })

  it('工具弹窗展示暴露名和全局状态', () => {
    expect(agentsViewSource).toContain('exposedToolName')
    expect(agentsViewSource).toContain('serverEnabled')
    expect(agentsViewSource).toContain('toolEnabled')
    expect(agentsViewSource).toContain('服务停用')
    expect(agentsViewSource).toContain('工具停用')
  })
})
```

- [ ] **Step 2: Run the frontend test and verify it fails**

Run:

```powershell
cd admin-web
npm run test -- agents-tool-binding.spec.ts
```

Expected: FAIL because the new types, API functions and Vue state do not exist.

- [ ] **Step 3: Add frontend types**

Append to `admin-web/src/types/agent.ts`:

```typescript
export interface AgentToolBindingRecord {
  bindingId: number
  agentId: number
  toolName: string
  providerType: string
  providerRefId: number
  serverKey?: string | null
  serverName?: string | null
  toolDisplayName?: string | null
  toolDescription?: string | null
  serverEnabled: boolean
  toolEnabled: boolean
  enabled: boolean
  sort: number
}

export interface AgentToolOptionRecord {
  toolName: string
  providerType: string
  providerRefId: number
  serverKey: string
  serverName: string
  remoteToolName: string
  exposedToolName: string
  displayName: string
  description?: string | null
  serverEnabled: boolean
  toolEnabled: boolean
}
```

- [ ] **Step 4: Add frontend API functions**

Modify import in `admin-web/src/api/agent.ts`:

```typescript
import type {
  AgentFormPayload,
  AgentOptionRecord,
  AgentRecord,
  AgentToolBindingRecord,
  AgentToolOptionRecord
} from '../types/agent'
```

Append functions:

```typescript
export async function fetchAgentToolBindings(agentId: number) {
  const response = await http.get<ApiResponse<AgentToolBindingRecord[]>>(`/agents/${agentId}/tools`)
  return response.data.data
}

export async function fetchAgentToolOptions(agentId: number) {
  const response = await http.get<ApiResponse<AgentToolOptionRecord[]>>(`/agents/${agentId}/tools/options`)
  return response.data.data
}

export async function updateAgentToolBindings(agentId: number, tools: AgentToolOptionRecord[]) {
  const response = await http.put<ApiResponse<AgentToolBindingRecord[]>>(`/agents/${agentId}/tools`, {
    tools: tools.map((tool, index) => ({
      toolName: tool.exposedToolName,
      providerType: tool.providerType,
      providerRefId: tool.providerRefId,
      sort: index
    }))
  })
  return response.data.data
}
```

- [ ] **Step 5: Add the tool binding modal to AgentsView**

Modify `admin-web/src/views/AgentsView.vue`.

Add this modal after the Skill modal:

```vue
    <n-modal
      v-model:show="showToolModal"
      preset="card"
      :title="toolModalAgent ? `${toolModalAgent.name} 绑定工具` : '绑定工具'"
      class="form-modal"
      :style="{ width: 'min(820px, calc(100vw - 32px))' }"
    >
      <div class="tool-toolbar">
        <n-input v-model:value="toolKeyword" clearable placeholder="搜索服务、工具或暴露名称" />
      </div>
      <n-data-table
        :columns="toolColumns"
        :data="filteredToolOptions"
        :loading="toolLoading"
        :pagination="{ pageSize: 8 }"
        :row-key="(row) => row.exposedToolName"
        :checked-row-keys="selectedToolNames"
        @update:checked-row-keys="handleToolSelectionChange"
      />
      <template #footer>
        <n-space justify="end">
          <n-button @click="showToolModal = false">取消</n-button>
          <n-button type="primary" :loading="toolSaving" @click="submitToolBindings">保存</n-button>
        </n-space>
      </template>
    </n-modal>
```

Update imports:

```typescript
import type { DataTableColumns, DataTableRowKey, SelectOption } from 'naive-ui'
import {
  createAgent,
  deleteAgent,
  fetchAgents,
  fetchAgentToolBindings,
  fetchAgentToolOptions,
  updateAgent,
  updateAgentToolBindings
} from '../api/agent'
import type { AgentFormPayload, AgentRecord, AgentToolOptionRecord } from '../types/agent'
```

Add state:

```typescript
const toolModalAgent = ref<AgentRecord | null>(null)
const showToolModal = ref(false)
const toolLoading = ref(false)
const toolSaving = ref(false)
const toolKeyword = ref('')
const toolOptions = ref<AgentToolOptionRecord[]>([])
const selectedToolNames = ref<DataTableRowKey[]>([])
```

Add computed data and columns:

```typescript
const filteredToolOptions = computed(() =>
  toolOptions.value.filter((item) => {
    const keywordText = toolKeyword.value.trim()
    if (!keywordText) {
      return true
    }
    return (
      item.serverName.includes(keywordText) ||
      item.serverKey.includes(keywordText) ||
      item.displayName.includes(keywordText) ||
      item.remoteToolName.includes(keywordText) ||
      item.exposedToolName.includes(keywordText) ||
      (item.description ?? '').includes(keywordText)
    )
  })
)

const toolColumns: DataTableColumns<AgentToolOptionRecord> = [
  { type: 'selection' },
  {
    title: 'MCP 服务',
    key: 'serverName',
    width: 150,
    render: (row) => row.serverName || row.serverKey
  },
  {
    title: '工具',
    key: 'displayName',
    width: 180,
    render: (row) => row.displayName || row.remoteToolName
  },
  {
    title: '暴露名称',
    key: 'exposedToolName',
    ellipsis: { tooltip: true }
  },
  {
    title: '全局状态',
    key: 'status',
    width: 160,
    render: (row) =>
      h(NSpace, { size: 6 }, {
        default: () => [
          h(NTag, { type: row.serverEnabled ? 'success' : 'warning', bordered: false, size: 'small' }, {
            default: () => (row.serverEnabled ? '服务启用' : '服务停用')
          }),
          h(NTag, { type: row.toolEnabled ? 'success' : 'warning', bordered: false, size: 'small' }, {
            default: () => (row.toolEnabled ? '工具启用' : '工具停用')
          })
        ]
      })
  }
]
```

Add the action button after Skill:

```typescript
          h(NButton, { size: 'small', tertiary: true, onClick: () => openTools(row) }, { default: () => '工具' }),
```

Add methods:

```typescript
async function openTools(agent: AgentRecord) {
  toolModalAgent.value = agent
  showToolModal.value = true
  toolLoading.value = true
  toolKeyword.value = ''
  try {
    const [optionData, bindingData] = await Promise.all([
      fetchAgentToolOptions(agent.agentId),
      fetchAgentToolBindings(agent.agentId)
    ])
    toolOptions.value = optionData
    selectedToolNames.value = bindingData
      .slice()
      .sort((a, b) => a.sort - b.sort)
      .map((item) => item.toolName)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载 Agent 工具绑定失败')
  } finally {
    toolLoading.value = false
  }
}

function handleToolSelectionChange(keys: DataTableRowKey[]) {
  selectedToolNames.value = keys
}

async function submitToolBindings() {
  if (!toolModalAgent.value) {
    return
  }
  toolSaving.value = true
  try {
    const selected = toolOptions.value.filter((item) => selectedToolNames.value.includes(item.exposedToolName))
    await updateAgentToolBindings(toolModalAgent.value.agentId, selected)
    message.success('Agent 工具绑定已保存')
    showToolModal.value = false
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存 Agent 工具绑定失败')
  } finally {
    toolSaving.value = false
  }
}
```

Add CSS:

```css
.tool-toolbar {
  margin-bottom: 12px;
}
```

- [ ] **Step 6: Run frontend test and type-check**

Run:

```powershell
cd admin-web
npm run test -- agents-tool-binding.spec.ts
npm run type-check
```

Expected: PASS.

- [ ] **Step 7: Commit Task 4**

```powershell
git add admin-web/src/types/agent.ts admin-web/src/api/agent.ts admin-web/src/views/AgentsView.vue admin-web/tests/agents-tool-binding.spec.ts
git commit -m "feat: 增加 Agent 工具绑定管理端"
```

---

### Task 5: 全量验证和收尾

**Files:**
- Verify only; no planned source edits unless tests expose issues.

- [ ] **Step 1: Run focused backend tests**

Run:

```powershell
mvn -pl agent-hub-tool -Dtest=AgentToolBindingServiceImplTest,AgentToolBindingControllerTest,McpToolProviderTest test
mvn -pl agent-hub-server -Dtest=AgentToolBindingMigrationSmokeTest test
mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test
```

Expected: all PASS.

- [ ] **Step 2: Run backend module tests**

Run:

```powershell
mvn test
```

Expected: all PASS.

- [ ] **Step 3: Run admin-web tests and build**

Run:

```powershell
cd admin-web
npm run test -- agents-tool-binding.spec.ts
npm run type-check
npm run build
```

Expected: all PASS.

- [ ] **Step 4: Inspect git status**

Run:

```powershell
git status --short
```

Expected: only intentional files are modified; unrelated pre-existing `admin-web/public/` and `user-web/public/` remain untracked if they were already present.

- [ ] **Step 5: Commit verification fixes if any**

If Task 5 required source fixes, commit only the exact files changed during Task 5. For example, if the verification fix touched `backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/McpToolProvider.java` and `backend/agent-hub-tool/src/test/java/com/agenthub/tool/mcp/McpToolProviderTest.java`, run:

```powershell
git add backend/agent-hub-tool/src/main/java/com/agenthub/tool/mcp/McpToolProvider.java backend/agent-hub-tool/src/test/java/com/agenthub/tool/mcp/McpToolProviderTest.java
git commit -m "test: 验证 Agent 工具绑定治理"
```

If no fixes were needed, do not create an empty commit.
