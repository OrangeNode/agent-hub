# AI Provider Model Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 AI 供应商管理、模型管理、独立 SSE 模块，以及用户端基础思考和对话落库闭环。

**Architecture:** 后端新增 `agent-hub-ai`、`agent-hub-chat`、`agent-hub-sse` 三个 Maven 模块；`agent-hub-server` 负责装配和 Flyway 迁移。管理端新增 AI 管理页面，用户端改为从后端读取模型、会话和消息，并消费统一 SSE 事件。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis Plus、Flyway、PostgreSQL、Vue 3、TypeScript、Naive UI、Pinia、EventSource。

---

## 文件结构

后端新增文件按模块划分：

- `backend/pom.xml`：新增 `agent-hub-ai`、`agent-hub-sse`、`agent-hub-chat` 模块。
- `backend/agent-hub-ai/pom.xml`：AI 模块依赖 `common`、`security` 和 Web/Jackson/MyBatis。
- `backend/agent-hub-sse/pom.xml`：SSE 模块依赖 `common` 和 Spring Web。
- `backend/agent-hub-chat/pom.xml`：Chat 模块依赖 `common`、`security`、`ai`、`sse` 和 MyBatis。
- `backend/agent-hub-server/pom.xml`：依赖新增三个模块。
- `backend/agent-hub-server/src/main/resources/db/migration/V2__init_ai_chat.sql`：新增 AI 和聊天表，并插入 AI 管理菜单。
- `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/**`：供应商实体、DTO、请求、命令、Mapper、Service、Controller。
- `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/**`：模型实体、DTO、请求、命令、Mapper、Service、Controller。
- `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/**`：LLM 统一抽象、OpenAI/Anthropic 协议适配、客户端工厂。
- `backend/agent-hub-sse/src/main/java/com/agenthub/sse/**`：SSE 事件枚举、事件 DTO、发送器。
- `backend/agent-hub-chat/src/main/java/com/agenthub/chat/**`：会话、消息、标题生成、聊天流式编排和 Controller。
- `admin-web/src/types/ai.ts`：管理端 AI 类型。
- `admin-web/src/api/ai.ts`：供应商和模型 API。
- `admin-web/src/router/route-registry.ts`：新增 AI 管理路由。
- `admin-web/src/views/AiProvidersView.vue`：供应商管理页。
- `admin-web/src/views/AiModelsView.vue`：模型管理页。
- `user-web/src/api/chat.ts`：聊天 API、会话 API、SSE URL 参数。
- `user-web/src/views/ChatView.vue`：接入后端模型、会话、消息和 SSE 事件。

测试文件：

- `backend/agent-hub-ai/src/test/java/com/agenthub/ai/provider/ProviderServiceImplTest.java`
- `backend/agent-hub-ai/src/test/java/com/agenthub/ai/model/ModelServiceImplTest.java`
- `backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm/LlmClientFactoryTest.java`
- `backend/agent-hub-chat/src/test/java/com/agenthub/chat/title/ConversationTitleServiceTest.java`
- `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`
- `backend/agent-hub-sse/src/test/java/com/agenthub/sse/SseEventSenderTest.java`
- `backend/agent-hub-server/src/test/java/com/agenthub/server/ai/AiChatMigrationSmokeTest.java`
- `admin-web/tests/ai-management.spec.ts`
- `user-web/tests/chat-stream.spec.ts`

---

### Task 1: 后端 Maven 模块和迁移骨架

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-hub-ai/pom.xml`
- Create: `backend/agent-hub-sse/pom.xml`
- Create: `backend/agent-hub-chat/pom.xml`
- Modify: `backend/agent-hub-server/pom.xml`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V2__init_ai_chat.sql`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/ai/AiChatMigrationSmokeTest.java`

- [ ] **Step 1: 写迁移冒烟测试**

创建测试类，验证 Flyway 能加载 `V2__init_ai_chat.sql` 并创建四张表。

```java
package com.agenthub.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AiChatMigrationSmokeTest {

    @Autowired
    DataSource dataSource;

    @Test
    void shouldCreateAiAndChatTables() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer providerCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'ai_provider'",
                Integer.class
        );
        Integer modelCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'ai_model'",
                Integer.class
        );
        Integer conversationCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'chat_conversation'",
                Integer.class
        );
        Integer messageCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'chat_message'",
                Integer.class
        );

        assertThat(providerCount).isEqualTo(1);
        assertThat(modelCount).isEqualTo(1);
        assertThat(conversationCount).isEqualTo(1);
        assertThat(messageCount).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; mvn -pl agent-hub-server -Dtest=AiChatMigrationSmokeTest test`

Expected: FAIL，原因是测试类或新增表不存在。

- [ ] **Step 3: 增加 Maven 模块**

在 `backend/pom.xml` 的 `<modules>` 中加入：

```xml
<module>agent-hub-ai</module>
<module>agent-hub-sse</module>
<module>agent-hub-chat</module>
```

创建 `agent-hub-ai/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.agenthub</groupId>
        <artifactId>backend</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>agent-hub-ai</artifactId>
    <name>agent-hub-ai</name>
    <dependencies>
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-core</artifactId>
            <version>3.5.16</version>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

`agent-hub-sse/pom.xml` 依赖 `agent-hub-common`、`spring-web`、`spring-boot-starter-test`。

`agent-hub-chat/pom.xml` 依赖 `agent-hub-common`、`agent-hub-security`、`agent-hub-ai`、`agent-hub-sse`、`mybatis-plus-core`、`spring-web`、`spring-tx`、`spring-boot-starter-validation`、`spring-boot-starter-test`。

在 `agent-hub-server/pom.xml` 中加入新增模块依赖：

```xml
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-ai</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-sse</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-chat</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 4: 创建数据库迁移**

创建 `V2__init_ai_chat.sql`，包含四张表、索引和菜单数据。菜单插入使用固定 id 从 6 开始，权限包含：

```sql
CREATE TABLE ai_provider (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    protocol_type VARCHAR(64) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    api_key_cipher TEXT,
    auth_type VARCHAR(64) NOT NULL,
    api_version VARCHAR(64),
    extra_headers TEXT,
    extra_body TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_provider_code UNIQUE (code)
);

CREATE TABLE ai_model (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    provider_id BIGINT NOT NULL,
    model_code VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    capability_type VARCHAR(32) NOT NULL,
    stream_supported BOOLEAN NOT NULL DEFAULT TRUE,
    reasoning_supported BOOLEAN NOT NULL DEFAULT FALSE,
    context_window INT,
    max_output_tokens INT,
    default_model BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_model_provider_code UNIQUE (provider_id, model_code),
    CONSTRAINT fk_ai_model_provider FOREIGN KEY (provider_id) REFERENCES ai_provider (id)
);

CREATE TABLE chat_conversation (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(30) NOT NULL,
    agent_key VARCHAR(64),
    model_id BIGINT,
    provider_id BIGINT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_conversation_model FOREIGN KEY (model_id) REFERENCES ai_model (id),
    CONSTRAINT fk_chat_conversation_provider FOREIGN KEY (provider_id) REFERENCES ai_provider (id)
);

CREATE TABLE chat_message (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT,
    reasoning_content TEXT,
    provider_id BIGINT,
    model_id BIGINT,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_chat_message_provider FOREIGN KEY (provider_id) REFERENCES ai_provider (id),
    CONSTRAINT fk_chat_message_model FOREIGN KEY (model_id) REFERENCES ai_model (id)
);
```

- [ ] **Step 5: 运行迁移测试确认通过**

Run: `cd backend; mvn -pl agent-hub-server -Dtest=AiChatMigrationSmokeTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/pom.xml backend/agent-hub-ai/pom.xml backend/agent-hub-sse/pom.xml backend/agent-hub-chat/pom.xml backend/agent-hub-server/pom.xml backend/agent-hub-server/src/main/resources/db/migration/V2__init_ai_chat.sql backend/agent-hub-server/src/test/java/com/agenthub/server/ai/AiChatMigrationSmokeTest.java
git commit -m "feat: 新增AI聊天后端模块和数据库迁移"
```

---

### Task 2: AI 供应商管理后端

**Files:**
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/entity/AiProvider.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/dto/AiProviderItem.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/command/CreateProviderCommand.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/command/UpdateProviderCommand.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/controller/request/CreateProviderRequest.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/controller/request/UpdateProviderRequest.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/mapper/AiProviderMapper.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/service/AiProviderService.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/service/impl/AiProviderServiceImpl.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider/controller/AiProviderController.java`
- Test: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/provider/ProviderServiceImplTest.java`

- [ ] **Step 1: 写供应商 Service 失败测试**

测试覆盖：创建时编码唯一、列表不返回密钥、更新空 `apiKey` 保留旧密钥、删除有关联模型时失败。

```java
package com.agenthub.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.ai.provider.command.CreateProviderCommand;
import com.agenthub.ai.provider.command.UpdateProviderCommand;
import com.agenthub.ai.provider.service.AiProviderService;
import com.agenthub.common.exception.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderServiceImplTest {

    @Test
    void shouldCreateProviderAndHideApiKey() {
        AiProviderService service = new InMemoryProviderServiceForTest(Map.of());

        var item = service.create(new CreateProviderCommand(
                "openai",
                "OpenAI",
                "OPENAI",
                "OPENAI_CHAT_COMPLETIONS",
                "https://api.openai.com",
                "sk-test",
                "BEARER",
                null,
                "{}",
                "{}",
                true,
                1
        ));

        assertThat(item.code()).isEqualTo("openai");
        assertThat(item.hasApiKey()).isTrue();
    }

    @Test
    void shouldRejectDuplicateCode() {
        AiProviderService service = new InMemoryProviderServiceForTest(Map.of());
        CreateProviderCommand command = new CreateProviderCommand(
                "openai", "OpenAI", "OPENAI", "OPENAI_CHAT_COMPLETIONS",
                "https://api.openai.com", "sk-test", "BEARER", null, "{}", "{}", true, 1
        );
        service.create(command);

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(BusinessException.class);
    }
}
```

测试中使用 `InMemoryProviderServiceForTest` 模拟 Mapper 行为；实现时也可以改成 Mockito，但断言必须保留同等覆盖。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; mvn -pl agent-hub-ai -Dtest=ProviderServiceImplTest test`

Expected: FAIL，原因是类不存在。

- [ ] **Step 3: 实现实体和 DTO**

`AiProvider` 使用 MyBatis Plus 注解：

```java
@TableName("ai_provider")
public class AiProvider {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String providerType;
    private String protocolType;
    private String baseUrl;
    private String apiKeyCipher;
    private String authType;
    private String apiVersion;
    private String extraHeaders;
    private String extraBody;
    private Boolean enabled;
    private Integer sort;
}
```

`AiProviderItem`：

```java
public record AiProviderItem(
        Long providerId,
        String code,
        String name,
        String providerType,
        String protocolType,
        String baseUrl,
        String authType,
        String apiVersion,
        boolean hasApiKey,
        boolean enabled,
        Integer sort
) {
}
```

- [ ] **Step 4: 实现命令、请求、Mapper、Service、Controller**

Controller 路径：

```java
@RestController
@RequestMapping("/api/ai/providers")
public class AiProviderController {
    @GetMapping
    public ApiResponse<List<AiProviderItem>> listProviders() {
        return ApiResponse.success(providerService.list());
    }
}
```

Service 规则：

```java
if (providerMapper.selectCount(new LambdaQueryWrapper<AiProvider>().eq(AiProvider::getCode, command.code())) > 0) {
    throw new BusinessException(ErrorCode.BAD_REQUEST, "供应商编码已存在");
}
```

API Key 本阶段先用明文占位保存到 `api_key_cipher`，方法名保留 `encryptApiKey`：

```java
private String encryptApiKey(String apiKey) {
    return apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
}
```

- [ ] **Step 5: 运行供应商测试**

Run: `cd backend; mvn -pl agent-hub-ai -Dtest=ProviderServiceImplTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/agent-hub-ai/src/main/java/com/agenthub/ai/provider backend/agent-hub-ai/src/test/java/com/agenthub/ai/provider/ProviderServiceImplTest.java
git commit -m "feat: 实现AI供应商管理后端"
```

---

### Task 3: AI 模型管理后端

**Files:**
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/entity/AiModel.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/dto/AiModelItem.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/command/CreateModelCommand.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/command/UpdateModelCommand.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/controller/request/CreateModelRequest.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/controller/request/UpdateModelRequest.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/mapper/AiModelMapper.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/service/AiModelService.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/service/impl/AiModelServiceImpl.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/controller/AiModelController.java`
- Test: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/model/ModelServiceImplTest.java`

- [ ] **Step 1: 写模型 Service 失败测试**

覆盖：供应商不存在时报错、同供应商模型编码唯一、设置默认模型会取消其他默认、`available` 只返回启用供应商下的启用模型。

```java
package com.agenthub.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.service.AiModelService;
import org.junit.jupiter.api.Test;

class ModelServiceImplTest {

    @Test
    void shouldReturnOnlyEnabledModelsOfEnabledProviders() {
        AiModelService service = new InMemoryModelServiceForTest();

        service.create(new CreateModelCommand(1L, "gpt-4o", "GPT-4o", "CHAT", true, false, 128000, 4096, true, true, 1));
        service.create(new CreateModelCommand(2L, "disabled-provider-model", "Disabled", "CHAT", true, false, 8000, 1024, false, true, 2));
        service.create(new CreateModelCommand(1L, "disabled-model", "Disabled Model", "CHAT", true, false, 8000, 1024, false, false, 3));

        var available = service.listAvailable();

        assertThat(available).extracting("modelCode").containsExactly("gpt-4o");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; mvn -pl agent-hub-ai -Dtest=ModelServiceImplTest test`

Expected: FAIL，原因是类不存在。

- [ ] **Step 3: 实现模型实体和 DTO**

`AiModelItem`：

```java
public record AiModelItem(
        Long modelId,
        Long providerId,
        String providerCode,
        String providerName,
        String modelCode,
        String displayName,
        String capabilityType,
        boolean streamSupported,
        boolean reasoningSupported,
        Integer contextWindow,
        Integer maxOutputTokens,
        boolean defaultModel,
        boolean enabled,
        Integer sort
) {
}
```

- [ ] **Step 4: 实现模型 CRUD 和可用模型接口**

Controller：

```java
@GetMapping("/available")
public ApiResponse<List<AiModelItem>> listAvailableModels() {
    return ApiResponse.success(modelService.listAvailable());
}
```

默认模型规则：

```java
if (Boolean.TRUE.equals(model.getDefaultModel())) {
    modelMapper.clearDefaultModelExcept(model.getId());
}
```

`AiModelMapper` 增加：

```java
@Update("update ai_model set default_model = false where id <> #{modelId}")
void clearDefaultModelExcept(Long modelId);
```

- [ ] **Step 5: 运行模型测试**

Run: `cd backend; mvn -pl agent-hub-ai -Dtest=ModelServiceImplTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/agent-hub-ai/src/main/java/com/agenthub/ai/model backend/agent-hub-ai/src/test/java/com/agenthub/ai/model/ModelServiceImplTest.java
git commit -m "feat: 实现AI模型管理后端"
```

---

### Task 4: LLM Client 抽象和协议适配

**Files:**
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmClient.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmClientFactory.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmMessage.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmStreamRequest.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmStreamChunk.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmStreamChunkType.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/openai/OpenAiCompatibleClient.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/openai/OpenAiChatCompletionsClient.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/openai/OpenAiResponsesClient.java`
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/anthropic/AnthropicMessagesClient.java`
- Test: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm/LlmClientFactoryTest.java`

- [ ] **Step 1: 写工厂选择测试**

```java
package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.anthropic.AnthropicMessagesClient;
import com.agenthub.ai.llm.openai.OpenAiCompatibleClient;
import org.junit.jupiter.api.Test;

class LlmClientFactoryTest {

    @Test
    void shouldChooseClientByProtocolType() {
        LlmClientFactory factory = new LlmClientFactory();

        assertThat(factory.create("OPENAI_COMPATIBLE")).isInstanceOf(OpenAiCompatibleClient.class);
        assertThat(factory.create("ANTHROPIC_MESSAGES")).isInstanceOf(AnthropicMessagesClient.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; mvn -pl agent-hub-ai -Dtest=LlmClientFactoryTest test`

Expected: FAIL，原因是类不存在。

- [ ] **Step 3: 实现统一抽象**

```java
public interface LlmClient {
    void stream(LlmStreamRequest request, java.util.function.Consumer<LlmStreamChunk> chunkConsumer);
}

public record LlmMessage(String role, String content) {
}

public record LlmStreamRequest(
        String baseUrl,
        String apiKey,
        String apiVersion,
        String model,
        java.util.List<LlmMessage> messages,
        Integer maxOutputTokens,
        boolean reasoningRequested
) {
}

public enum LlmStreamChunkType {
    REASONING_DELTA,
    ANSWER_DELTA,
    USAGE,
    DONE,
    ERROR
}

public record LlmStreamChunk(LlmStreamChunkType type, String content, String errorMessage) {
    public static LlmStreamChunk answer(String content) {
        return new LlmStreamChunk(LlmStreamChunkType.ANSWER_DELTA, content, null);
    }
}
```

- [ ] **Step 4: 实现客户端工厂和协议客户端骨架**

`LlmClientFactory`：

```java
public class LlmClientFactory {
    public LlmClient create(String protocolType) {
        return switch (protocolType) {
            case "OPENAI_CHAT_COMPLETIONS" -> new OpenAiChatCompletionsClient();
            case "OPENAI_RESPONSES" -> new OpenAiResponsesClient();
            case "ANTHROPIC_MESSAGES" -> new AnthropicMessagesClient();
            case "OPENAI_COMPATIBLE" -> new OpenAiCompatibleClient();
            default -> throw new IllegalArgumentException("不支持的模型协议: " + protocolType);
        };
    }
}
```

客户端先实现可测试骨架，真实 HTTP 流解析在 Task 8 接入：

```java
public class OpenAiCompatibleClient implements LlmClient {
    @Override
    public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
        chunkConsumer.accept(LlmStreamChunk.answer(""));
        chunkConsumer.accept(new LlmStreamChunk(LlmStreamChunkType.DONE, null, null));
    }
}
```

- [ ] **Step 5: 运行工厂测试**

Run: `cd backend; mvn -pl agent-hub-ai -Dtest=LlmClientFactoryTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm/LlmClientFactoryTest.java
git commit -m "feat: 新增LLM协议适配抽象"
```

---

### Task 5: 独立 SSE 模块

**Files:**
- Create: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventType.java`
- Create: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventData.java`
- Create: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventSender.java`
- Test: `backend/agent-hub-sse/src/test/java/com/agenthub/sse/SseEventSenderTest.java`

- [ ] **Step 1: 写 SSE 发送器测试**

```java
package com.agenthub.sse;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseEventSenderTest {

    @Test
    void shouldSendNamedEvent() {
        SseEventSender sender = new SseEventSender();
        SseEmitter emitter = new SseEmitter(1000L);

        assertThatCode(() -> sender.send(emitter, SseEventType.DONE, new SseEventData.Done()))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; mvn -pl agent-hub-sse -Dtest=SseEventSenderTest test`

Expected: FAIL，原因是类不存在。

- [ ] **Step 3: 实现事件类型和 DTO**

```java
public enum SseEventType {
    CONVERSATION("conversation"),
    MESSAGE("message"),
    REASONING_DELTA("reasoning_delta"),
    ANSWER_DELTA("answer_delta"),
    MESSAGE_COMPLETED("message_completed"),
    ERROR("error"),
    DONE("done");

    private final String eventName;

    SseEventType(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
```

```java
public sealed interface SseEventData permits SseEventData.Conversation, SseEventData.Message, SseEventData.Delta, SseEventData.MessageCompleted, SseEventData.Error, SseEventData.Done {
    record Conversation(Long conversationId, String title) implements SseEventData {}
    record Message(Long messageId, String role) implements SseEventData {}
    record Delta(String content) implements SseEventData {}
    record MessageCompleted(Long messageId, String status) implements SseEventData {}
    record Error(String code, String message) implements SseEventData {}
    record Done() implements SseEventData {}
}
```

- [ ] **Step 4: 实现发送器**

```java
public class SseEventSender {
    public void send(SseEmitter emitter, SseEventType eventType, SseEventData data) throws IOException {
        emitter.send(SseEmitter.event().name(eventType.eventName()).data(data));
    }

    public void complete(SseEmitter emitter) {
        emitter.complete();
    }

    public void completeWithError(SseEmitter emitter, Throwable throwable) {
        emitter.completeWithError(throwable);
    }
}
```

- [ ] **Step 5: 运行 SSE 测试**

Run: `cd backend; mvn -pl agent-hub-sse -Dtest=SseEventSenderTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/agent-hub-sse/src/main/java/com/agenthub/sse backend/agent-hub-sse/src/test/java/com/agenthub/sse/SseEventSenderTest.java
git commit -m "feat: 新增统一SSE事件模块"
```

---

### Task 6: Chat 会话、消息和标题生成

**Files:**
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/conversation/entity/ChatConversation.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/entity/ChatMessage.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/conversation/dto/ConversationItem.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/dto/MessageItem.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/conversation/mapper/ChatConversationMapper.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/mapper/ChatMessageMapper.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/title/ConversationTitleService.java`
- Test: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/title/ConversationTitleServiceTest.java`

- [ ] **Step 1: 写标题服务测试**

```java
package com.agenthub.chat.title;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationTitleServiceTest {

    @Test
    void shouldFallbackToFirstFifteenVisibleCharacters() {
        ConversationTitleService service = new ConversationTitleService();

        String title = service.generateFallbackTitle("请帮我重新整理模型管理和供应商管理接口");

        assertThat(title).isEqualTo("请帮我重新整理模型管理");
        assertThat(title.length()).isLessThanOrEqualTo(15);
    }

    @Test
    void shouldUseDefaultTitleForBlankPrompt() {
        ConversationTitleService service = new ConversationTitleService();

        assertThat(service.generateFallbackTitle("   ")).isEqualTo("新会话");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; mvn -pl agent-hub-chat -Dtest=ConversationTitleServiceTest test`

Expected: FAIL，原因是类不存在。

- [ ] **Step 3: 实现实体、DTO、Mapper**

`ConversationItem`：

```java
public record ConversationItem(Long conversationId, String title, String agentKey, Long modelId, String status) {
}
```

`MessageItem`：

```java
public record MessageItem(Long messageId, Long conversationId, String role, String content, String reasoningContent, String status) {
}
```

Mapper 均继承 `BaseMapper`：

```java
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {
}
```

- [ ] **Step 4: 实现标题服务**

```java
public class ConversationTitleService {
    public String generateFallbackTitle(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return "新会话";
        }
        String compact = prompt.replaceAll("\\s+", "");
        return compact.length() <= 15 ? compact : compact.substring(0, 15);
    }
}
```

- [ ] **Step 5: 运行标题测试**

Run: `cd backend; mvn -pl agent-hub-chat -Dtest=ConversationTitleServiceTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/agent-hub-chat/src/main/java/com/agenthub/chat backend/agent-hub-chat/src/test/java/com/agenthub/chat/title/ConversationTitleServiceTest.java
git commit -m "feat: 新增聊天会话消息模型和标题生成"
```

---

### Task 7: Chat 流式服务和 Controller

**Files:**
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/ChatService.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/controller/ChatController.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/controller/request/ChatStreamRequest.java`
- Test: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: 写 Chat Service 测试**

覆盖：新会话创建、用户消息落库、助手消息先 `STREAMING`、LLM chunk 映射到 SSE 事件、完成后状态为 `COMPLETED`。

```java
package com.agenthub.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.sse.SseEventType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatServiceImplTest {

    @Test
    void shouldCreateConversationAndEmitAnswerDelta() {
        List<SseEventType> emittedTypes = new ArrayList<>();
        FakeChatService service = new FakeChatService(List.of(LlmStreamChunk.answer("你好")), emittedTypes);

        service.stream(2L, null, 1L, "general", "你好");

        assertThat(service.savedMessages()).hasSize(2);
        assertThat(emittedTypes).contains(SseEventType.CONVERSATION, SseEventType.MESSAGE, SseEventType.ANSWER_DELTA, SseEventType.MESSAGE_COMPLETED, SseEventType.DONE);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test`

Expected: FAIL，原因是服务不存在。

- [ ] **Step 3: 实现 ChatService 接口**

```java
public interface ChatService {
    SseEmitter stream(Long userId, Long conversationId, Long modelId, String agentKey, String prompt);
    List<ConversationItem> listConversations(Long userId);
    List<MessageItem> listMessages(Long userId, Long conversationId);
    void deleteConversation(Long userId, Long conversationId);
}
```

- [ ] **Step 4: 实现 Controller**

`ChatController`：

```java
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    @GetMapping("/stream")
    public SseEmitter stream(
            Authentication authentication,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) String agentKey,
            @RequestParam String prompt
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return chatService.stream(loginUser.userId(), conversationId, modelId, agentKey, prompt);
    }
}
```

- [ ] **Step 5: 实现 Service 编排**

核心顺序：

```java
ChatConversation conversation = conversationId == null
        ? createConversation(userId, modelId, agentKey, titleService.generateFallbackTitle(prompt))
        : requireConversation(userId, conversationId);

ChatMessage userMessage = saveUserMessage(userId, conversation.getId(), prompt);
ChatMessage assistantMessage = saveAssistantStreamingMessage(userId, conversation.getId(), modelId);

sender.send(emitter, SseEventType.CONVERSATION, new SseEventData.Conversation(conversation.getId(), conversation.getTitle()));
sender.send(emitter, SseEventType.MESSAGE, new SseEventData.Message(assistantMessage.getId(), "assistant"));
```

LLM chunk 映射：

```java
switch (chunk.type()) {
    case REASONING_DELTA -> appendReasoningAndSend(chunk.content());
    case ANSWER_DELTA -> appendAnswerAndSend(chunk.content());
    case ERROR -> failAssistantMessage(chunk.errorMessage());
    case DONE -> completeAssistantMessage();
    case USAGE -> { }
}
```

- [ ] **Step 6: 运行 Chat Service 测试**

Run: `cd backend; mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test`

Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add backend/agent-hub-chat/src/main/java/com/agenthub/chat backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java
git commit -m "feat: 实现聊天流式服务和会话接口"
```

---

### Task 8: OpenAI Compatible 和 Anthropic 流解析

**Files:**
- Modify: `backend/agent-hub-ai/pom.xml`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/openai/OpenAiCompatibleClient.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/openai/OpenAiChatCompletionsClient.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/anthropic/AnthropicMessagesClient.java`
- Test: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm/OpenAiCompatibleClientTest.java`
- Test: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm/AnthropicMessagesClientTest.java`

- [ ] **Step 1: 写 OpenAI chunk 解析测试**

```java
@Test
void shouldParseOpenAiAnswerAndReasoningDelta() {
    OpenAiCompatibleClient client = new OpenAiCompatibleClient();

    assertThat(client.parseDataLine("{\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\",\"content\":\"回答\"}}]}"))
            .containsExactly(
                    new LlmStreamChunk(LlmStreamChunkType.REASONING_DELTA, "思考", null),
                    new LlmStreamChunk(LlmStreamChunkType.ANSWER_DELTA, "回答", null)
            );
}
```

- [ ] **Step 2: 写 Anthropic chunk 解析测试**

```java
@Test
void shouldParseAnthropicTextDelta() {
    AnthropicMessagesClient client = new AnthropicMessagesClient();

    assertThat(client.parseEvent("content_block_delta", "{\"delta\":{\"type\":\"text_delta\",\"text\":\"回答\"}}"))
            .containsExactly(new LlmStreamChunk(LlmStreamChunkType.ANSWER_DELTA, "回答", null));
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd backend; mvn -pl agent-hub-ai -Dtest=OpenAiCompatibleClientTest,AnthropicMessagesClientTest test`

Expected: FAIL，原因是解析方法不存在。

- [ ] **Step 4: 实现解析方法和真实 HTTP 流请求**

使用 Spring `RestClient` 或 JDK `HttpClient`。为减少依赖，优先使用 JDK `HttpClient`：

```java
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(request.baseUrl() + "/v1/chat/completions"))
        .header("Authorization", "Bearer " + request.apiKey())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
```

OpenAI Compatible 解析 `data: ` 行：

```java
if ("[DONE]".equals(payload)) {
    return List.of(new LlmStreamChunk(LlmStreamChunkType.DONE, null, null));
}
JsonNode delta = mapper.readTree(payload).path("choices").path(0).path("delta");
```

Anthropic 解析命名事件和 `data:`：

```java
if ("content_block_delta".equals(eventName)) {
    JsonNode delta = mapper.readTree(payload).path("delta");
    if ("text_delta".equals(delta.path("type").asText())) {
        return List.of(LlmStreamChunk.answer(delta.path("text").asText()));
    }
}
```

- [ ] **Step 5: 运行协议解析测试**

Run: `cd backend; mvn -pl agent-hub-ai -Dtest=OpenAiCompatibleClientTest,AnthropicMessagesClientTest test`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add backend/agent-hub-ai/pom.xml backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm
git commit -m "feat: 实现OpenAI和Anthropic流式解析"
```

---

### Task 9: Security 兼容 EventSource token

**Files:**
- Modify: `backend/agent-hub-server/src/main/java/com/agenthub/server/config/SecurityConfig.java`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/config/SecurityConfigIntegrationTest.java`

- [ ] **Step 1: 写查询参数 token 鉴权测试**

在现有 `SecurityConfigIntegrationTest` 中新增测试：

```java
@Test
void shouldAuthenticateChatStreamWithTokenQueryParameter() throws Exception {
    String token = jwtService.generateToken(new LoginUser(2L, "user", List.of("USER")));

    mockMvc.perform(get("/api/chat/stream").param("token", token).param("prompt", "你好"))
            .andExpect(status().isOk());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend; mvn -pl agent-hub-server -Dtest=SecurityConfigIntegrationTest test`

Expected: FAIL，原因是过滤器只读取 `Authorization` 头。

- [ ] **Step 3: 修改 JWT 过滤器读取 token 参数**

在 `JwtAuthenticationFilter` 中提取 token：

```java
private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
        return header.substring(7);
    }
    if ("/api/chat/stream".equals(request.getRequestURI())) {
        String queryToken = request.getParameter("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken;
        }
    }
    return null;
}
```

`doFilterInternal` 改为使用 `resolveToken(request)`。

- [ ] **Step 4: 运行 Security 测试**

Run: `cd backend; mvn -pl agent-hub-server -Dtest=SecurityConfigIntegrationTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/agent-hub-server/src/main/java/com/agenthub/server/config/SecurityConfig.java backend/agent-hub-server/src/test/java/com/agenthub/server/config/SecurityConfigIntegrationTest.java
git commit -m "feat: 支持聊天SSE查询参数鉴权"
```

---

### Task 10: 管理端 AI 供应商和模型页面

**Files:**
- Create: `admin-web/src/types/ai.ts`
- Create: `admin-web/src/api/ai.ts`
- Modify: `admin-web/src/router/route-registry.ts`
- Create: `admin-web/src/views/AiProvidersView.vue`
- Create: `admin-web/src/views/AiModelsView.vue`
- Test: `admin-web/tests/ai-management.spec.ts`

- [ ] **Step 1: 写管理端测试**

```ts
import { test, expect } from '@playwright/test'

test('AI management routes are registered', async ({ page }) => {
  await page.goto('/ai/providers')
  await expect(page.getByRole('heading', { name: '供应商管理' })).toBeVisible()

  await page.goto('/ai/models')
  await expect(page.getByRole('heading', { name: '模型管理' })).toBeVisible()
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd admin-web; npm run test -- ai-management.spec.ts`

Expected: FAIL，原因是页面和路由不存在。

- [ ] **Step 3: 实现类型和 API**

`admin-web/src/types/ai.ts`：

```ts
export interface AiProviderRecord {
  providerId: number
  code: string
  name: string
  providerType: string
  protocolType: string
  baseUrl: string
  authType: string
  apiVersion?: string | null
  hasApiKey: boolean
  enabled: boolean
  sort: number
}

export interface AiModelRecord {
  modelId: number
  providerId: number
  providerCode: string
  providerName: string
  modelCode: string
  displayName: string
  capabilityType: string
  streamSupported: boolean
  reasoningSupported: boolean
  contextWindow?: number | null
  maxOutputTokens?: number | null
  defaultModel: boolean
  enabled: boolean
  sort: number
}
```

`admin-web/src/api/ai.ts` 使用现有 `http`：

```ts
export async function fetchAiProviders() {
  const response = await http.get<ApiResponse<AiProviderRecord[]>>('/ai/providers')
  return response.data.data
}
```

- [ ] **Step 4: 注册路由**

在 `route-registry.ts` 添加：

```ts
{
  path: '/ai/providers',
  name: 'ai-providers',
  title: '供应商管理',
  icon: 'settings',
  permission: 'ai:provider:list',
  component: () => import('../views/AiProvidersView.vue')
},
{
  path: '/ai/models',
  name: 'ai-models',
  title: '模型管理',
  icon: 'grid',
  permission: 'ai:model:list',
  component: () => import('../views/AiModelsView.vue')
}
```

- [ ] **Step 5: 实现两个管理页面**

页面沿用 `SystemUsersView.vue` 模式：

```vue
<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>供应商管理</h2>
        <p>维护 OpenAI、Anthropic 和兼容协议供应商。</p>
      </div>
      <n-space>
        <n-button @click="loadData">刷新</n-button>
        <n-button type="primary" @click="openCreate">新增供应商</n-button>
      </n-space>
    </div>
    <n-card :bordered="false" size="small">
      <n-data-table :columns="columns" :data="providers" :loading="loading" :pagination="false" />
    </n-card>
  </div>
</template>
```

模型页提供供应商下拉、模型编码、展示名称、能力类型、是否默认、启用状态。

- [ ] **Step 6: 运行前端检查**

Run: `cd admin-web; npm run type-check`

Expected: PASS。

Run: `cd admin-web; npm run build`

Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add admin-web/src/types/ai.ts admin-web/src/api/ai.ts admin-web/src/router/route-registry.ts admin-web/src/views/AiProvidersView.vue admin-web/src/views/AiModelsView.vue admin-web/tests/ai-management.spec.ts
git commit -m "feat: 新增AI供应商和模型管理页面"
```

---

### Task 11: 用户端聊天接入后端会话、消息、模型和 SSE

**Files:**
- Modify: `user-web/src/api/chat.ts`
- Modify: `user-web/src/views/ChatView.vue`
- Test: `user-web/tests/chat-stream.spec.ts`

- [ ] **Step 1: 写用户端测试**

```ts
import { test, expect } from '@playwright/test'

test('chat page loads models from backend', async ({ page }) => {
  await page.route('/api/ai/models/available', async (route) => {
    await route.fulfill({
      json: { code: 0, message: 'ok', data: [{ modelId: 1, displayName: 'GPT-4o', modelCode: 'gpt-4o' }] }
    })
  })

  await page.goto('/chat')
  await expect(page.getByRole('button', { name: /GPT-4o|发送/ })).toBeVisible()
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd user-web; npm run test -- chat-stream.spec.ts`

Expected: FAIL，原因是模型列表仍是硬编码或测试文件不存在。

- [ ] **Step 3: 扩展 chat API**

`user-web/src/api/chat.ts`：

```ts
export interface AvailableModel {
  modelId: number
  displayName: string
  modelCode: string
  reasoningSupported: boolean
}

export interface ConversationRecord {
  conversationId: number
  title: string
  agentKey?: string | null
  modelId?: number | null
  status: string
}

export interface MessageRecord {
  messageId: number
  conversationId: number
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  reasoningContent?: string | null
  status: string
}

export async function fetchAvailableModels() {
  const response = await http.get<ApiResponse<AvailableModel[]>>('/ai/models/available')
  return response.data.data
}

export function openChatStream(params: { prompt: string; token: string; modelId?: number; conversationId?: number | null; agentKey?: string }) {
  const url = new URL('/api/chat/stream', window.location.origin)
  url.searchParams.set('prompt', params.prompt)
  url.searchParams.set('token', params.token)
  if (params.modelId) url.searchParams.set('modelId', String(params.modelId))
  if (params.conversationId) url.searchParams.set('conversationId', String(params.conversationId))
  if (params.agentKey) url.searchParams.set('agentKey', params.agentKey)
  return new EventSource(url)
}
```

- [ ] **Step 4: 修改 ChatView 状态模型**

把硬编码 `modelOptions` 替换为后端数据：

```ts
const availableModels = ref<AvailableModel[]>([])
const selectedModelId = ref<number | null>(null)

const modelOptions = computed(() =>
  availableModels.value.map((model) => ({
    label: model.displayName,
    value: model.modelId
  }))
)
```

`onMounted` 加载：

```ts
async function loadModels() {
  availableModels.value = await fetchAvailableModels()
  selectedModelId.value = availableModels.value[0]?.modelId ?? null
}
```

- [ ] **Step 5: 处理命名 SSE 事件**

```ts
eventSource.addEventListener('conversation', (event) => {
  const payload = JSON.parse((event as MessageEvent).data)
  activeConversationId.value = String(payload.conversationId)
  upsertConversation(payload.conversationId, payload.title)
})

eventSource.addEventListener('reasoning_delta', (event) => {
  const payload = JSON.parse((event as MessageEvent).data)
  assistantMessage.reasoningContent += payload.content
})

eventSource.addEventListener('answer_delta', (event) => {
  const payload = JSON.parse((event as MessageEvent).data)
  assistantMessage.content += payload.content
})
```

- [ ] **Step 6: 接入会话列表和消息列表**

新增：

```ts
export async function fetchConversations() {
  const response = await http.get<ApiResponse<ConversationRecord[]>>('/chat/conversations')
  return response.data.data
}

export async function fetchConversationMessages(conversationId: number) {
  const response = await http.get<ApiResponse<MessageRecord[]>>(`/chat/conversations/${conversationId}/messages`)
  return response.data.data
}
```

`ChatView` 左侧最近会话和切换会话使用这两个接口。

- [ ] **Step 7: 运行用户端检查**

Run: `cd user-web; npm run type-check`

Expected: PASS。

Run: `cd user-web; npm run build`

Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add user-web/src/api/chat.ts user-web/src/views/ChatView.vue user-web/tests/chat-stream.spec.ts
git commit -m "feat: 用户端接入后端聊天闭环"
```

---

### Task 12: 全量验证和收尾

**Files:**
- Modify as needed: `README.md`
- Modify as needed: `docker/README.md`

- [ ] **Step 1: 运行后端全量测试**

Run: `cd backend; mvn test`

Expected: PASS。

- [ ] **Step 2: 运行管理端检查**

Run: `cd admin-web; npm run type-check`

Expected: PASS。

Run: `cd admin-web; npm run build`

Expected: PASS。

- [ ] **Step 3: 运行用户端检查**

Run: `cd user-web; npm run type-check`

Expected: PASS。

Run: `cd user-web; npm run build`

Expected: PASS。

- [ ] **Step 4: 手工联调**

启动依赖和后端：

```powershell
cd docker
docker compose up -d
cd ..\backend
mvn -pl agent-hub-server spring-boot:run
```

启动前端：

```powershell
cd admin-web
npm run dev
```

```powershell
cd user-web
npm run dev
```

验证：

- 管理端登录后能进入 `/ai/providers`。
- 新增 OpenAI Compatible 或 Anthropic 供应商。
- 新增启用模型并设置默认模型。
- 用户端进入 `/chat` 后能看到模型下拉。
- 新会话发送第一条消息后，后端创建会话和两条消息。
- SSE 能收到 `conversation`、`message`、`answer_delta`、`message_completed`、`done`。
- 新会话标题不超过 15 字。

- [ ] **Step 5: 文档补充**

在 `README.md` 增加 AI 聊天验证命令和环境变量说明：

```markdown
## AI 供应商配置

管理端可在 AI 管理中维护供应商和模型。用户端聊天会读取启用供应商下的启用模型。
```

- [ ] **Step 6: 最终提交**

```bash
git add README.md docker/README.md
git commit -m "docs: 补充AI聊天联调说明"
```

---

## 自检清单

- 设计文档中的供应商管理由 Task 2 和 Task 10 覆盖。
- 设计文档中的模型管理由 Task 3 和 Task 10 覆盖。
- OpenAI 和 Anthropic 协议兼容由 Task 4 和 Task 8 覆盖。
- 独立 SSE 模块由 Task 5 覆盖。
- 会话和消息落库由 Task 6 和 Task 7 覆盖。
- 标题不超过 15 字由 Task 6 和 Task 12 覆盖。
- EventSource token 兼容由 Task 9 覆盖。
- 用户端模型、会话、消息和 SSE 对接由 Task 11 覆盖。
- 全量验证由 Task 12 覆盖。

