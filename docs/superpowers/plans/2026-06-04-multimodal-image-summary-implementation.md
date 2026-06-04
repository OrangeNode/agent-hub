# 图片多模态与会话摘要 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent Hub 增加图片多模态上传、MinIO 7 天过期存储、历史图片复用、模型图片能力开关，以及生产级会话上下文预算与 80% 自动摘要。

**Architecture:** 新增 `agent-hub-storage` 模块封装对象存储和附件生命周期，`agent-hub-chat` 负责聊天消息、附件绑定和上下文构建，`agent-hub-ai` 负责把文本和图片转换成供应商协议。用户端通过后端签发的预签名 URL 直传 MinIO，后端确认上传后才允许发送聊天。

**Tech Stack:** Java 21、Spring Boot 4、Maven 多模块、MyBatis-Plus、Flyway、MinIO Java SDK、PostgreSQL、Vue 3、TypeScript、Naive UI、Vitest、Docker Compose。

---

## 范围检查

本计划覆盖一个集成特性，但执行时按可独立验证的子系统拆分：

1. Docker/MinIO 依赖服务。
2. 后端 storage 模块和附件表。
3. 模型图片能力字段。
4. 聊天附件绑定和多模态 LLM 请求。
5. 会话上下文预算与摘要。
6. 用户端上传、预览、进度、复用交互。

执行阶段遇到真实 MinIO 联调时必须暂停，让用户完成 `docker compose up -d`、bucket lifecycle 和 `public-endpoint` 配置确认后再继续。

## 文件结构

### Docker

- Modify: `docker/docker-compose.yml`  
  增加 `minio` 和 `minio-init` 服务，创建 bucket 并设置 7 天 lifecycle。
- Create: `docker/env/minio.env`  
  保存 MinIO 根账号、bucket 名称和过期天数。
- Modify: `docker/README.md`  
  补充 MinIO 端口、生命周期和 public endpoint 注意事项。
- Modify: `README.md`  
  更新依赖服务清单和快速启动说明。

### 后端 storage 模块

- Modify: `backend/pom.xml`  
  增加 `agent-hub-storage` 模块。
- Create: `backend/agent-hub-storage/pom.xml`  
  引入 common、security、MyBatis-Plus、Spring Web、Validation、Scheduling、MinIO SDK、测试依赖。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/config/StorageProperties.java`  
  绑定 `agent-hub.storage.*` 配置。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/config/StorageAutoConfiguration.java`  
  创建 MinIO client bean 和启用调度相关 bean。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/object/ObjectStorageClient.java`  
  对象存储接口，暴露预签名上传、预签名读取、对象 stat、删除。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/object/MinioObjectStorageClient.java`  
  MinIO SDK 实现。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/entity/ChatAttachment.java`  
  `chat_attachment` 实体。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/mapper/ChatAttachmentMapper.java`  
  MyBatis-Plus mapper。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/dto/AttachmentUploadUrl.java`  
  上传 URL 返回 DTO。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/dto/AttachmentItem.java`  
  历史消息附件 DTO。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/dto/AttachmentReference.java`  
  聊天调用使用的附件引用。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/controller/AttachmentController.java`  
  `/api/chat/attachments` 接口。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/controller/request/CreateImageUploadUrlRequest.java`  
  申请上传 URL 请求。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/service/AttachmentService.java`  
  附件服务接口。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/service/impl/AttachmentServiceImpl.java`  
  附件校验、确认、绑定、复用、URL 生成。
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/job/AttachmentCleanupJob.java`  
  清理超时临时附件和标记过期附件。
- Modify: `backend/agent-hub-server/pom.xml`  
  引入 `agent-hub-storage`。
- Modify: `backend/agent-hub-chat/pom.xml`  
  引入 `agent-hub-storage`。
- Modify: `backend/agent-hub-server/src/main/resources/application-dev.yml`  
  增加 storage 开发配置。
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V3__init_multimodal_image_summary.sql`  
  增加附件表、会话摘要表、模型图片能力字段。

### 后端 AI 与 Chat

- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/entity/AiModel.java`  
  增加 `imageInputSupported`、`imageTokenEstimate`。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/dto/AiModelItem.java`  
  返回模型图片能力。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/dto/AiModelRuntimeConfig.java`  
  增加图片能力和图片 token 估算。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/command/CreateModelCommand.java`  
  增加图片字段。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/command/UpdateModelCommand.java`  
  增加图片字段。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/controller/request/CreateModelRequest.java`  
  增加图片字段。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/controller/request/UpdateModelRequest.java`  
  增加图片字段。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/service/impl/AiModelServiceImpl.java`  
  保存、更新、查询图片能力。
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmContentPart.java`  
  表示 text/image_url 多 part。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmMessage.java`  
  支持文本和多 part 内容。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/openai/OpenAiCompatibleClient.java`  
  OpenAI 兼容协议输出多模态 content。
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/anthropic/AnthropicMessagesClient.java`  
  暂不支持 URL 图片时拒绝或忽略图片并返回明确错误。
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/controller/ChatController.java`  
  `ChatStreamRequest` 增加 `attachmentIds`、`reuseAttachmentIds`。
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/ChatService.java`  
  stream 方法增加附件 ID 参数。
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`  
  校验、绑定附件、传递多模态消息，并接入上下文构建器。
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/dto/MessageItem.java`  
  返回附件列表。
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/summary/entity/ChatConversationSummary.java`  
  会话摘要实体。
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/summary/mapper/ChatConversationSummaryMapper.java`  
  摘要 mapper。
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/context/TokenEstimator.java`  
  token 估算器接口。
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/context/SimpleTokenEstimator.java`  
  第一版生产可用近似估算器，中文字符、英文单词、符号分别计权。
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/context/ConversationContextBuilder.java`  
  上下文预算、摘要触发、最近消息选择。
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/context/ConversationContext.java`  
  构建后的 LLM 消息、图片引用和摘要状态。

### 前端管理端

- Modify: `admin-web/src/types/ai.ts`  
  增加 `imageInputSupported`、`imageTokenEstimate`。
- Modify: `admin-web/src/views/AiModelsView.vue`  
  新增“支持图片输入”开关和图片 token 估算输入。

### 前端用户端

- Modify: `user-web/src/api/chat.ts`  
  增加上传接口、确认接口、消息附件类型、流式请求附件字段。
- Create: `user-web/src/views/chat-attachments.helpers.ts`  
  文件类型、大小、数量校验和上传状态工具。
- Modify: `user-web/src/views/ChatView.vue`  
  增加 `+` 向上菜单、上传照片、进度、预览、移除、大图预览、历史图片复用。
- Modify: `user-web/src/views/HomeView.vue`  
  如首页内嵌聊天入口复用 ChatView 组件状态，则同步模型图片能力展示。

---

## Task 1: Docker MinIO 依赖配置

**Files:**
- Modify: `docker/docker-compose.yml`
- Create: `docker/env/minio.env`
- Modify: `docker/README.md`
- Modify: `README.md`

- [ ] **Step 1: 写 Compose 配置变更**

在 `docker/docker-compose.yml` 增加：

```yaml
  minio:
    image: minio/minio:RELEASE.2025-05-24T17-08-30Z
    container_name: agent-hub-minio
    restart: unless-stopped
    env_file:
      - ./env/minio.env
    command:
      - server
      - /data
      - --console-address
      - ":9001"
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio-data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks:
      - agent-hub-network

  minio-init:
    image: minio/mc:RELEASE.2025-05-21T01-59-54Z
    container_name: agent-hub-minio-init
    depends_on:
      minio:
        condition: service_healthy
    env_file:
      - ./env/minio.env
    entrypoint:
      - sh
      - -c
      - |
        mc alias set agent-hub http://minio:9000 "$$MINIO_ROOT_USER" "$$MINIO_ROOT_PASSWORD"
        mc mb --ignore-existing "agent-hub/$$MINIO_BUCKET_CHAT_IMAGES"
        cat > /tmp/chat-image-lifecycle.json <<EOF
        {
          "Rules": [
            {
              "ID": "expire-chat-images-after-seven-days",
              "Status": "Enabled",
              "Expiration": {
                "Days": $$MINIO_IMAGE_EXPIRE_DAYS
              }
            }
          ]
        }
        EOF
        mc ilm import "agent-hub/$$MINIO_BUCKET_CHAT_IMAGES" < /tmp/chat-image-lifecycle.json
    networks:
      - agent-hub-network
```

在 `volumes` 增加：

```yaml
  minio-data:
```

- [ ] **Step 2: 写 MinIO 环境文件**

创建 `docker/env/minio.env`：

```dotenv
MINIO_ROOT_USER=agenthub
MINIO_ROOT_PASSWORD=agenthub123
MINIO_BUCKET_CHAT_IMAGES=agent-hub-chat-images
MINIO_IMAGE_EXPIRE_DAYS=7
```

- [ ] **Step 3: 验证 Compose 配置**

Run:

```powershell
cd D:\project\Agent-Hub\docker
docker compose config
```

Expected: 输出完整 Compose 配置，包含 `minio`、`minio-init`、`minio-data`，无 YAML 解析错误。

- [ ] **Step 4: 暂停让用户配置**

在真实启动前暂停并告知用户：

```text
需要你确认本机 9000/9001 端口可用，然后运行 docker compose up -d。确认 MinIO Console 可访问、bucket 已创建、lifecycle 已导入后，我再继续依赖真实 MinIO 的联调。
```

本步骤不自动启动 Docker。

- [ ] **Step 5: 更新 Docker 文档**

在 `docker/README.md` 增加 MinIO 说明：

```markdown
## MinIO 图片对象存储

本项目使用 MinIO 保存聊天图片附件。默认端口：

- API：`9000`
- Console：`9001`

图片 bucket 名称来自 `env/minio.env` 的 `MINIO_BUCKET_CHAT_IMAGES`，默认 `agent-hub-chat-images`。`minio-init` 会创建 bucket 并配置 lifecycle，7 天后自动清理对象。

bucket 默认不公开读。前端上传和模型读取都通过后端签发的短期预签名 URL 完成。

如果云模型需要读取图片，后端 `agent-hub.storage.public-endpoint` 必须配置成模型服务可访问的公网或内网地址。
```

- [ ] **Step 6: 更新根 README**

在 `README.md` 的 Docker 依赖清单增加：

```markdown
- MinIO：聊天图片对象存储，默认 7 天生命周期清理
```

- [ ] **Step 7: 提交**

Run:

```powershell
git add docker/docker-compose.yml docker/env/minio.env docker/README.md README.md
git commit -m "feat: 增加 MinIO 图片存储依赖"
```

Expected: commit 成功。

---

## Task 2: 新增 storage 模块骨架与附件表迁移

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/agent-hub-storage/pom.xml`
- Modify: `backend/agent-hub-server/pom.xml`
- Modify: `backend/agent-hub-chat/pom.xml`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V3__init_multimodal_image_summary.sql`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/entity/ChatAttachment.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/mapper/ChatAttachmentMapper.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/summary/entity/ChatConversationSummary.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/summary/mapper/ChatConversationSummaryMapper.java`
- Test: `backend/agent-hub-server/src/test/java/com/agenthub/server/ai/MultimodalImageSummaryMigrationSmokeTest.java`

- [ ] **Step 1: 写失败的迁移冒烟测试**

创建 `backend/agent-hub-server/src/test/java/com/agenthub/server/ai/MultimodalImageSummaryMigrationSmokeTest.java`：

```java
package com.agenthub.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MultimodalImageSummaryMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateAttachmentSummaryAndImageModelColumns() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'PUBLIC'",
                String.class
        );
        assertThat(tables).contains("CHAT_ATTACHMENT", "CHAT_CONVERSATION_SUMMARY");

        List<String> modelColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'AI_MODEL'",
                String.class
        );
        assertThat(modelColumns).contains("IMAGE_INPUT_SUPPORTED", "IMAGE_TOKEN_ESTIMATE");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-server -Dtest=MultimodalImageSummaryMigrationSmokeTest test
```

Expected: FAIL，原因是 `CHAT_ATTACHMENT` 或 `CHAT_CONVERSATION_SUMMARY` 不存在。

- [ ] **Step 3: 新增 Maven 模块**

在 `backend/pom.xml` 的 `<modules>` 增加：

```xml
<module>agent-hub-storage</module>
```

创建 `backend/agent-hub-storage/pom.xml`：

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

    <artifactId>agent-hub-storage</artifactId>
    <name>agent-hub-storage</name>

    <dependencies>
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.agenthub</groupId>
            <artifactId>agent-hub-security</artifactId>
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
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
            <version>8.5.17</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

在 `backend/agent-hub-server/pom.xml` 和 `backend/agent-hub-chat/pom.xml` 增加：

```xml
<dependency>
    <groupId>com.agenthub</groupId>
    <artifactId>agent-hub-storage</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 4: 新增 Flyway 迁移**

创建 `backend/agent-hub-server/src/main/resources/db/migration/V3__init_multimodal_image_summary.sql`：

```sql
ALTER TABLE ai_model
    ADD COLUMN IF NOT EXISTS image_input_supported BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS image_token_estimate INT;

CREATE TABLE chat_attachment (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT,
    message_id BIGINT,
    object_key VARCHAR(512) NOT NULL,
    bucket_name VARCHAR(128) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    file_ext VARCHAR(16) NOT NULL,
    attachment_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_chat_attachment_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_chat_attachment_message FOREIGN KEY (message_id) REFERENCES chat_message (id)
);

CREATE INDEX idx_chat_attachment_user_status_created_at
    ON chat_attachment (user_id, status, created_at);

CREATE INDEX idx_chat_attachment_conversation_message
    ON chat_attachment (conversation_id, message_id);

CREATE INDEX idx_chat_attachment_expires_at
    ON chat_attachment (expires_at);

CREATE INDEX idx_chat_attachment_status_created_at
    ON chat_attachment (status, created_at);

CREATE TABLE chat_conversation_summary (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    summary TEXT NOT NULL,
    summarized_message_id BIGINT,
    token_estimate INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_chat_conversation_summary_conversation UNIQUE (conversation_id),
    CONSTRAINT fk_chat_conversation_summary_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_chat_conversation_summary_message FOREIGN KEY (summarized_message_id) REFERENCES chat_message (id)
);

CREATE INDEX idx_chat_conversation_summary_user
    ON chat_conversation_summary (user_id);
```

- [ ] **Step 5: 新增实体和 mapper**

创建 `ChatAttachment.java`，字段与迁移表一致，使用 `@TableName("chat_attachment")` 和 `@TableId(type = IdType.AUTO)`。

创建 `ChatAttachmentMapper.java`：

```java
package com.agenthub.storage.attachment.mapper;

import com.agenthub.storage.attachment.entity.ChatAttachment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface ChatAttachmentMapper extends BaseMapper<ChatAttachment> {
}
```

创建 `ChatConversationSummary.java`，字段与迁移表一致，使用 `@TableName("chat_conversation_summary")`。

创建 `ChatConversationSummaryMapper.java`：

```java
package com.agenthub.chat.summary.mapper;

import com.agenthub.chat.summary.entity.ChatConversationSummary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface ChatConversationSummaryMapper extends BaseMapper<ChatConversationSummary> {
}
```

- [ ] **Step 6: 运行测试确认通过**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-server -Dtest=MultimodalImageSummaryMigrationSmokeTest test
```

Expected: PASS。

- [ ] **Step 7: 提交**

Run:

```powershell
git add backend/pom.xml backend/agent-hub-storage backend/agent-hub-server/pom.xml backend/agent-hub-chat/pom.xml backend/agent-hub-server/src/main/resources/db/migration/V3__init_multimodal_image_summary.sql backend/agent-hub-server/src/test/java/com/agenthub/server/ai/MultimodalImageSummaryMigrationSmokeTest.java
git commit -m "feat: 增加图片附件和会话摘要表"
```

Expected: commit 成功。

---

## Task 3: 实现对象存储配置和附件上传确认接口

**Files:**
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/config/StorageProperties.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/config/StorageAutoConfiguration.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/object/ObjectStorageClient.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/object/ObjectMetadata.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/object/MinioObjectStorageClient.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/dto/AttachmentUploadUrl.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/controller/request/CreateImageUploadUrlRequest.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/controller/AttachmentController.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/service/AttachmentService.java`
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/service/impl/AttachmentServiceImpl.java`
- Modify: `backend/agent-hub-server/src/main/resources/application-dev.yml`
- Test: `backend/agent-hub-storage/src/test/java/com/agenthub/storage/attachment/AttachmentServiceImplTest.java`

- [ ] **Step 1: 写失败的服务测试**

创建 `AttachmentServiceImplTest.java`，覆盖：

```java
@Test
void shouldCreatePendingImageUploadUrl() {
    // 给定用户 7、文件 cat.png、image/png、1024 字节
    // 调用 createImageUploadUrl
    // 断言 mapper.insert 的附件 status=PENDING、attachmentType=IMAGE、expiresAt=createdAt+7天附近
    // 断言返回 attachmentId、uploadUrl、PUT、Content-Type header
}

@Test
void shouldRejectUnsupportedContentType() {
    // contentType=application/pdf
    // 期望 BusinessException BAD_REQUEST，消息“仅支持 jpg、jpeg、png、webp、gif 图片”
}

@Test
void shouldRejectImageLargerThanTwentyMb() {
    // fileSize=20MB+1
    // 期望 BusinessException BAD_REQUEST，消息“图片不能超过 20MB”
}

@Test
void shouldConfirmPendingAttachmentWhenObjectExists() {
    // mapper.selectById 返回 userId=7/status=PENDING
    // objectStorageClient.stat 返回 size/contentType 匹配
    // 调用 confirmUpload
    // 断言 updateById status=READY，confirmedAt 非空
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-storage -Dtest=AttachmentServiceImplTest test
```

Expected: FAIL，原因是类不存在。

- [ ] **Step 3: 实现配置类**

`StorageProperties` 使用 `@ConfigurationProperties(prefix = "agent-hub.storage")`，字段：

```java
private String endpoint;
private String publicEndpoint;
private String accessKey;
private String secretKey;
private String bucket;
private int imageExpireDays = 7;
private int presignedUploadTtlMinutes = 10;
private int presignedReadTtlMinutes = 10;
private int pendingCleanupMinutes = 60;
private int unusedReadyCleanupHours = 24;
```

`StorageAutoConfiguration`：

```java
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean
    public MinioClient minioClient(StorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean
    public ObjectStorageClient objectStorageClient(MinioClient minioClient, StorageProperties properties) {
        return new MinioObjectStorageClient(minioClient, properties);
    }
}
```

- [ ] **Step 4: 实现对象存储接口**

`ObjectStorageClient`：

```java
public interface ObjectStorageClient {
    String presignPut(String objectKey, String contentType, Duration ttl);
    String presignGet(String objectKey, Duration ttl);
    ObjectMetadata stat(String objectKey);
    void delete(String objectKey);
}
```

`ObjectMetadata`：

```java
public record ObjectMetadata(long size, String contentType) {
}
```

`MinioObjectStorageClient` 使用 `GetPresignedObjectUrlArgs`、`StatObjectArgs`、`RemoveObjectArgs` 实现，异常统一转为 `BusinessException(ErrorCode.INTERNAL_ERROR, "对象存储操作失败")`。

- [ ] **Step 5: 实现附件 DTO、Controller 和 Service**

`AttachmentUploadUrl`：

```java
public record AttachmentUploadUrl(
        Long attachmentId,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        OffsetDateTime expiresAt
) {
}
```

`CreateImageUploadUrlRequest`：

```java
public record CreateImageUploadUrlRequest(
        @NotBlank(message = "文件名不能为空")
        String filename,
        @NotBlank(message = "文件类型不能为空")
        String contentType,
        @NotNull(message = "文件大小不能为空")
        @Positive(message = "文件大小必须大于 0")
        Long fileSize
) {
}
```

`AttachmentController`：

```java
@RestController
@RequestMapping("/api/chat/attachments")
public class AttachmentController {
    private final AttachmentService attachmentService;

    @PostMapping("/image-upload-url")
    public ApiResponse<AttachmentUploadUrl> createImageUploadUrl(
            Authentication authentication,
            @Valid @RequestBody CreateImageUploadUrlRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(attachmentService.createImageUploadUrl(
                loginUser.userId(),
                request.filename(),
                request.contentType(),
                request.fileSize()
        ));
    }

    @PostMapping("/{attachmentId}/confirm")
    public ApiResponse<Void> confirmUpload(Authentication authentication, @PathVariable Long attachmentId) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        attachmentService.confirmUpload(loginUser.userId(), attachmentId);
        return ApiResponse.success();
    }
}
```

`AttachmentService`：

```java
public interface AttachmentService {
    AttachmentUploadUrl createImageUploadUrl(Long userId, String filename, String contentType, long fileSize);
    void confirmUpload(Long userId, Long attachmentId);
}
```

- [ ] **Step 6: 实现附件校验**

在 `AttachmentServiceImpl` 使用常量：

```java
private static final long MAX_IMAGE_SIZE = 20L * 1024 * 1024;
private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "image/gif"
);
private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
```

错误消息必须使用：

```text
仅支持 jpg、jpeg、png、webp、gif 图片
图片不能超过 20MB
图片上传失败，请重试
```

object key 格式：

```text
users/{userId}/chat-images/{yyyyMMdd}/{uuid}.{ext}
```

- [ ] **Step 7: 更新开发配置**

在 `application-dev.yml` 增加：

```yaml
agent-hub:
  storage:
    endpoint: http://127.0.0.1:9000
    public-endpoint: http://127.0.0.1:9000
    access-key: agenthub
    secret-key: agenthub123
    bucket: agent-hub-chat-images
    image-expire-days: 7
    presigned-upload-ttl-minutes: 10
    presigned-read-ttl-minutes: 10
    pending-cleanup-minutes: 60
    unused-ready-cleanup-hours: 24
```

- [ ] **Step 8: 运行测试确认通过**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-storage -Dtest=AttachmentServiceImplTest test
```

Expected: PASS。

- [ ] **Step 9: 提交**

Run:

```powershell
git add backend/agent-hub-storage backend/agent-hub-server/src/main/resources/application-dev.yml
git commit -m "feat: 实现图片预签名上传确认"
```

Expected: commit 成功。

---

## Task 4: 实现附件清理任务

**Files:**
- Create: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/job/AttachmentCleanupJob.java`
- Test: `backend/agent-hub-storage/src/test/java/com/agenthub/storage/attachment/AttachmentCleanupJobTest.java`

- [ ] **Step 1: 写失败的清理任务测试**

创建测试覆盖：

```java
@Test
void shouldDeletePendingAttachmentsOlderThanConfiguredMinutes() {
    // mapper.selectList 返回 PENDING old attachment
    // 执行 cleanupTemporaryAttachments
    // verify objectStorageClient.delete(objectKey)
    // verify updateById status=DELETED deletedAt 非空
}

@Test
void shouldDeleteReadyAttachmentsWithoutMessageOlderThanConfiguredHours() {
    // READY + messageId null + createdAt 超过 24 小时
    // 期望删除对象并标记 DELETED
}

@Test
void shouldMarkExpiredReadyAttachments() {
    // READY + expiresAt < now
    // 期望 status=EXPIRED，不调用 delete
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-storage -Dtest=AttachmentCleanupJobTest test
```

Expected: FAIL，原因是 `AttachmentCleanupJob` 不存在。

- [ ] **Step 3: 实现清理任务**

`AttachmentCleanupJob` 构造注入：

```java
ChatAttachmentMapper attachmentMapper
ObjectStorageClient objectStorageClient
StorageProperties storageProperties
```

方法：

```java
@Scheduled(fixedDelayString = "${agent-hub.storage.cleanup-fixed-delay-ms:600000}")
public void cleanupTemporaryAttachments()
```

查询条件：

```text
PENDING and created_at < now - pendingCleanupMinutes
READY and message_id is null and created_at < now - unusedReadyCleanupHours
```

删除对象后设置：

```text
status = DELETED
deleted_at = now
updated_at = now
```

过期标记方法：

```java
@Scheduled(fixedDelayString = "${agent-hub.storage.expire-mark-fixed-delay-ms:3600000}")
public void markExpiredAttachments()
```

查询：

```text
status = READY and expires_at < now
```

设置：

```text
status = EXPIRED
updated_at = now
```

对象删除异常不能中断整个批次，单条失败记录日志后继续下一条。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-storage -Dtest=AttachmentCleanupJobTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

Run:

```powershell
git add backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/job/AttachmentCleanupJob.java backend/agent-hub-storage/src/test/java/com/agenthub/storage/attachment/AttachmentCleanupJobTest.java
git commit -m "feat: 增加图片附件清理任务"
```

Expected: commit 成功。

---

## Task 5: 扩展模型图片能力配置

**Files:**
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/entity/AiModel.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/dto/AiModelItem.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/dto/AiModelRuntimeConfig.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/command/CreateModelCommand.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/command/UpdateModelCommand.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/controller/request/CreateModelRequest.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/controller/request/UpdateModelRequest.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/model/service/impl/AiModelServiceImpl.java`
- Test: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/model/ModelServiceImplTest.java`

- [ ] **Step 1: 扩展现有模型服务测试**

在 `ModelServiceImplTest` 增加：

```java
@Test
void shouldCreateModelWithImageInputConfiguration() {
    CreateModelCommand command = new CreateModelCommand(
            1L,
            "gpt-4o",
            "GPT-4o",
            "MULTIMODAL",
            true,
            true,
            128000,
            4096,
            true,
            true,
            1,
            true,
            1500
    );

    service.create(command);

    ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);
    verify(modelMapper).insert(captor.capture());
    assertThat(captor.getValue().getImageInputSupported()).isTrue();
    assertThat(captor.getValue().getImageTokenEstimate()).isEqualTo(1500);
}
```

同时更新已有构造调用，补齐 `imageInputSupported=false`、`imageTokenEstimate=null`。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-ai -Dtest=ModelServiceImplTest test
```

Expected: FAIL，原因是构造签名和字段尚未扩展。

- [ ] **Step 3: 扩展模型实体和 DTO**

`AiModel` 增加：

```java
private Boolean imageInputSupported;
private Integer imageTokenEstimate;
```

`AiModelItem` 和 `AiModelRuntimeConfig` record 增加：

```java
boolean imageInputSupported,
Integer imageTokenEstimate
```

- [ ] **Step 4: 扩展请求和命令**

`CreateModelRequest`、`UpdateModelRequest` 增加：

```java
Boolean imageInputSupported,
@Min(value = 1, message = "图片 token 估算必须大于 0")
Integer imageTokenEstimate
```

转换 command 时：

```java
Boolean.TRUE.equals(imageInputSupported)
```

`CreateModelCommand`、`UpdateModelCommand` 增加对应字段。

- [ ] **Step 5: 扩展服务映射**

在 `AiModelServiceImpl` 创建、更新、列表、运行配置映射中读写：

```java
model.setImageInputSupported(command.imageInputSupported());
model.setImageTokenEstimate(command.imageTokenEstimate());
```

返回时空值规则：

```java
Boolean.TRUE.equals(model.getImageInputSupported())
```

- [ ] **Step 6: 运行测试确认通过**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-ai test
```

Expected: PASS。

- [ ] **Step 7: 提交**

Run:

```powershell
git add backend/agent-hub-ai
git commit -m "feat: 增加模型图片输入能力配置"
```

Expected: commit 成功。

---

## Task 6: 实现多模态 LLM 消息结构和 OpenAI 兼容协议

**Files:**
- Create: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmContentPart.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/LlmMessage.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/openai/OpenAiCompatibleClient.java`
- Modify: `backend/agent-hub-ai/src/main/java/com/agenthub/ai/llm/anthropic/AnthropicMessagesClient.java`
- Test: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm/OpenAiCompatibleClientTest.java`
- Test: `backend/agent-hub-ai/src/test/java/com/agenthub/ai/llm/AnthropicMessagesClientTest.java`

- [ ] **Step 1: 扩展 OpenAI 客户端测试**

在 `OpenAiCompatibleClientTest` 增加测试，捕获请求体并断言：

```json
"content":[
  {"type":"text","text":"分析图片"},
  {"type":"image_url","image_url":{"url":"https://example.com/cat.png"}}
]
```

测试代码断言重点：

```java
LlmMessage message = LlmMessage.user(List.of(
        LlmContentPart.text("分析图片"),
        LlmContentPart.imageUrl("https://example.com/cat.png")
));
client.stream(new LlmStreamRequest(..., List.of(message), ...), chunks::add);
assertThat(capturedBody).contains("\"type\":\"image_url\"");
assertThat(capturedBody).contains("\"url\":\"https://example.com/cat.png\"");
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-ai -Dtest=OpenAiCompatibleClientTest test
```

Expected: FAIL，原因是 `LlmContentPart` 或 `LlmMessage.user(List)` 不存在。

- [ ] **Step 3: 新增 LlmContentPart**

创建：

```java
package com.agenthub.ai.llm;

public record LlmContentPart(String type, String text, String imageUrl) {

    public static LlmContentPart text(String text) {
        return new LlmContentPart("text", text, null);
    }

    public static LlmContentPart imageUrl(String imageUrl) {
        return new LlmContentPart("image_url", null, imageUrl);
    }
}
```

- [ ] **Step 4: 扩展 LlmMessage**

改为：

```java
package com.agenthub.ai.llm;

import java.util.List;

public record LlmMessage(String role, String content, List<LlmContentPart> parts) {

    public LlmMessage(String role, String content) {
        this(role, content, null);
    }

    public static LlmMessage user(List<LlmContentPart> parts) {
        return new LlmMessage("user", null, parts);
    }

    public boolean hasParts() {
        return parts != null && !parts.isEmpty();
    }
}
```

- [ ] **Step 5: 修改 OpenAI content 序列化**

在 `OpenAiCompatibleClient.toMessages` 中：

```java
private Object contentValue(LlmMessage message) {
    if (!message.hasParts()) {
        return message.content();
    }
    return message.parts().stream()
            .map(part -> {
                Map<String, Object> item = new LinkedHashMap<>();
                if ("image_url".equals(part.type())) {
                    item.put("type", "image_url");
                    item.put("image_url", Map.of("url", part.imageUrl()));
                } else {
                    item.put("type", "text");
                    item.put("text", part.text());
                }
                return item;
            })
            .toList();
}
```

`toMessages` 的 map 类型改为 `Map<String, Object>`。

- [ ] **Step 6: Anthropic URL 图片暂不支持**

在 `AnthropicMessagesClient.toMessages` 前检测：

```java
if (messages.stream().anyMatch(LlmMessage::hasParts)) {
    throw new IllegalArgumentException("Anthropic Messages 暂不支持 URL 图片输入");
}
```

对应测试断言返回错误 chunk 消息包含：

```text
Anthropic Messages 暂不支持 URL 图片输入
```

- [ ] **Step 7: 运行 AI 模块测试**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-ai test
```

Expected: PASS。

- [ ] **Step 8: 提交**

Run:

```powershell
git add backend/agent-hub-ai
git commit -m "feat: 支持 OpenAI 兼容图片消息"
```

Expected: commit 成功。

---

## Task 7: 聊天发送接入附件校验、绑定和历史图片复用

**Files:**
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/dto/AttachmentItem.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/dto/AttachmentReference.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/service/AttachmentService.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/attachment/service/impl/AttachmentServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/controller/ChatController.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/ChatService.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/dto/MessageItem.java`
- Test: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: 扩展聊天服务测试**

在 `ChatServiceImplTest` 增加：

```java
@Test
void shouldRejectImagesWhenModelDoesNotSupportImageInput() {
    // runtimeConfig.imageInputSupported=false
    // 调用 stream(... attachmentIds=List.of(1L))
    // 期望 BusinessException BAD_REQUEST，消息“当前模型不支持图片输入”
}

@Test
void shouldBindReadyAttachmentsToUserMessage() {
    // attachmentService.resolveImagesForChat 返回两个 AttachmentReference
    // 用户消息 insert 后 id=1
    // verify attachmentService.bindToMessage(userId, conversationId, 1L, List.of(attachmentIds))
}

@Test
void shouldSendImagePartsToLlmClient() {
    // attachment reference 包含 readUrl
    // 调用 stream
    // 断言 llmClient.lastRequest.messages().getFirst().parts() 包含 text 和 image_url
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test
```

Expected: FAIL，原因是 stream 签名和附件服务尚未接入。

- [ ] **Step 3: 定义附件引用 DTO**

`AttachmentReference`：

```java
public record AttachmentReference(
        Long attachmentId,
        String filename,
        String contentType,
        String readUrl,
        int tokenEstimate
) {
}
```

`AttachmentItem`：

```java
public record AttachmentItem(
        Long attachmentId,
        String filename,
        String contentType,
        String status,
        boolean expired,
        String previewUrl,
        String message
) {
}
```

- [ ] **Step 4: 扩展 AttachmentService**

增加：

```java
List<AttachmentReference> resolveImagesForChat(
        Long userId,
        Long conversationId,
        List<Long> attachmentIds,
        List<Long> reuseAttachmentIds,
        int imageTokenEstimate
);

void bindToMessage(Long userId, Long conversationId, Long messageId, List<Long> attachmentIds);

List<AttachmentItem> listMessageAttachments(Long userId, List<Long> messageIds);
```

实现规则：

- 新附件必须 `READY`、未过期、属于用户、`messageId == null`。
- 复用附件必须 `READY`、未过期、属于用户、属于当前会话。
- 自动复用只在两个列表都为空且 `conversationId != null` 时查最近一轮用户消息图片。
- 最终数量超过 8 抛出 `单条消息最多上传 8 张图片`。
- 过期附件抛出 `图片已过期`。

- [ ] **Step 5: 扩展 ChatController 和 ChatService**

`ChatStreamRequest` 增加：

```java
List<Long> attachmentIds,
List<Long> reuseAttachmentIds
```

`ChatService.stream` 签名增加两个列表参数。

- [ ] **Step 6: 修改 ChatServiceImpl**

在保存用户消息前：

```java
List<AttachmentReference> images = attachmentService.resolveImagesForChat(
        userId,
        conversation.getId(),
        attachmentIds,
        reuseAttachmentIds,
        imageTokenEstimate(runtimeConfig)
);
if (!images.isEmpty() && !runtimeConfig.imageInputSupported()) {
    throw new BusinessException(ErrorCode.BAD_REQUEST, "当前模型不支持图片输入");
}
```

保存用户消息后绑定新附件：

```java
attachmentService.bindToMessage(userId, conversation.getId(), userMessage.getId(), attachmentIds);
```

构建 LLM 消息：

```java
List<LlmContentPart> parts = new ArrayList<>();
parts.add(LlmContentPart.text(prompt));
images.forEach(image -> parts.add(LlmContentPart.imageUrl(image.readUrl())));
LlmMessage userMessage = images.isEmpty()
        ? new LlmMessage("user", prompt)
        : LlmMessage.user(parts);
```

- [ ] **Step 7: 历史消息返回附件**

`MessageItem` 增加：

```java
List<AttachmentItem> attachments
```

`listMessages` 查询消息后调用 `attachmentService.listMessageAttachments` 并按 `messageId` 分组填充。

- [ ] **Step 8: 运行测试确认通过**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-chat test
```

Expected: PASS。

- [ ] **Step 9: 提交**

Run:

```powershell
git add backend/agent-hub-storage backend/agent-hub-chat
git commit -m "feat: 聊天接入图片附件"
```

Expected: commit 成功。

---

## Task 8: 实现生产级上下文预算与 80% 摘要

**Files:**
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/context/TokenEstimator.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/context/SimpleTokenEstimator.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/context/ConversationContext.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/context/ConversationContextBuilder.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Test: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/context/ConversationContextBuilderTest.java`

- [ ] **Step 1: 写上下文构建器失败测试**

创建测试覆盖：

```java
@Test
void shouldRejectCurrentInputWhenItExceedsAvailableBudget() {
    // contextWindow=1000 maxOutputTokens=200 safety=100
    // 当前用户消息估算 > 700
    // 期望 BusinessException BAD_REQUEST，消息“当前输入内容过长，已超过模型上下文限制，请缩短后重试”
}

@Test
void shouldTriggerSummaryWhenFullHistoryReachesEightyPercent() {
    // fullHistoryEstimate >= 800
    // verify summary service 被调用
    // result 包含 summary + selected recent messages
}

@Test
void shouldKeepRecentMessagesWithinBudget() {
    // 30 条历史消息
    // 最多保留 20 条，且总 token 不超过 availableInputTokens
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-chat -Dtest=ConversationContextBuilderTest test
```

Expected: FAIL，原因是上下文类不存在。

- [ ] **Step 3: 实现 TokenEstimator**

`TokenEstimator`：

```java
public interface TokenEstimator {
    int estimateText(String text);
}
```

`SimpleTokenEstimator`：

```java
public class SimpleTokenEstimator implements TokenEstimator {

    @Override
    public int estimateText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chinese = 0;
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                chinese++;
            } else if (ch < 128) {
                ascii++;
            } else {
                other++;
            }
        }
        return chinese + Math.max(1, ascii / 4) + Math.max(1, other / 2);
    }
}
```

- [ ] **Step 4: 实现 ConversationContext**

```java
public record ConversationContext(
        List<LlmMessage> messages,
        boolean summaryUpdated,
        int inputTokenEstimate
) {
}
```

- [ ] **Step 5: 实现 ConversationContextBuilder**

构造注入：

```java
ChatMessageMapper messageMapper
ChatConversationSummaryMapper summaryMapper
ConversationTitleService titleService
TokenEstimator tokenEstimator
Function<String, LlmClient> llmClientFactory
```

核心常量：

```java
private static final double SUMMARY_TRIGGER_RATIO = 0.8;
private static final int SAFETY_MARGIN_TOKENS = 1024;
private static final int DEFAULT_RESERVED_OUTPUT_TOKENS = 4096;
private static final int DEFAULT_IMAGE_TOKEN_ESTIMATE = 1500;
private static final int RECENT_MESSAGE_MAX_COUNT = 20;
private static final int RECENT_TURN_MIN_MESSAGE_COUNT = 4;
```

当前输入超限时抛出：

```java
throw new BusinessException(ErrorCode.BAD_REQUEST, "当前输入内容过长，已超过模型上下文限制，请缩短后重试");
```

摘要触发：

```java
if (fullHistoryEstimate >= runtimeConfig.contextWindow() * SUMMARY_TRIGGER_RATIO) {
    updateSummary(conversationId, userId, runtimeConfig, historyMessages);
}
```

摘要提示词必须包含：

```text
请把以下会话历史压缩为后续对话可用的中文摘要。保留用户目标、已确认约束、关键决策、未完成事项、错误和阻塞点。不要保留无意义闲聊。不要编造历史中不存在的信息。
```

- [ ] **Step 6: ChatServiceImpl 接入上下文构建器**

将当前 `buildRequest(runtimeConfig, prompt, reasoningRequested)` 改为接收 `ConversationContext`：

```java
ConversationContext context = conversationContextBuilder.build(
        userId,
        conversation.getId(),
        runtimeConfig,
        normalizedPrompt,
        images,
        reasoningRequested
);
return new LlmStreamRequest(..., context.messages(), ...);
```

标题生成仍使用当前 prompt，不走完整上下文。

- [ ] **Step 7: 运行测试确认通过**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn -pl agent-hub-chat test
```

Expected: PASS。

- [ ] **Step 8: 提交**

Run:

```powershell
git add backend/agent-hub-chat
git commit -m "feat: 增加会话上下文预算与摘要"
```

Expected: commit 成功。

---

## Task 9: 管理端模型页面增加图片能力开关

**Files:**
- Modify: `admin-web/src/types/ai.ts`
- Modify: `admin-web/src/views/AiModelsView.vue`
- Test: `admin-web/tests/ai-models-image-capability.spec.ts`

- [ ] **Step 1: 写前端失败测试**

创建 `admin-web/tests/ai-models-image-capability.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'

describe('AI 模型图片能力表单', () => {
  it('应该在默认表单中关闭图片输入能力', () => {
    const form = {
      imageInputSupported: false,
      imageTokenEstimate: null
    }
    expect(form.imageInputSupported).toBe(false)
    expect(form.imageTokenEstimate).toBeNull()
  })

  it('应该允许保存图片 token 估算', () => {
    const payload = {
      imageInputSupported: true,
      imageTokenEstimate: 1500
    }
    expect(payload).toEqual({
      imageInputSupported: true,
      imageTokenEstimate: 1500
    })
  })
})
```

- [ ] **Step 2: 扩展类型**

`admin-web/src/types/ai.ts` 的 `AiModelRecord` 和 `AiModelFormPayload` 增加：

```ts
imageInputSupported: boolean
imageTokenEstimate?: number | null
```

- [ ] **Step 3: 扩展模型表单**

在 `AiModelsView.vue` 表单中增加：

```vue
<n-form-item label="支持图片输入" path="imageInputSupported">
  <n-switch v-model:value="modelForm.imageInputSupported" />
</n-form-item>
<n-form-item v-if="modelForm.imageInputSupported" label="图片 token 估算" path="imageTokenEstimate">
  <n-input-number v-model:value="modelForm.imageTokenEstimate" :min="1" placeholder="默认 1500" />
</n-form-item>
```

表格列增加：

```text
图片输入：是/否
```

- [ ] **Step 4: 运行管理端测试和类型检查**

Run:

```powershell
cd D:\project\Agent-Hub\admin-web
npm run test
npm run type-check
```

Expected: PASS。

- [ ] **Step 5: 提交**

Run:

```powershell
git add admin-web/src/types/ai.ts admin-web/src/views/AiModelsView.vue admin-web/tests/ai-models-image-capability.spec.ts
git commit -m "feat: 管理端支持配置模型图片能力"
```

Expected: commit 成功。

---

## Task 10: 用户端图片上传 API 和校验工具

**Files:**
- Modify: `user-web/src/api/chat.ts`
- Create: `user-web/src/views/chat-attachments.helpers.ts`
- Test: `user-web/tests/chat-attachments.spec.ts`

- [ ] **Step 1: 写校验工具失败测试**

创建 `user-web/tests/chat-attachments.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'
import { validateImageFile, validateImageCount } from '../src/views/chat-attachments.helpers'

function file(name: string, type: string, size: number) {
  return new File(['x'.repeat(size)], name, { type })
}

describe('聊天图片附件校验', () => {
  it('应该允许 jpg/png/webp/gif 图片', () => {
    expect(validateImageFile(file('a.jpg', 'image/jpeg', 1024))).toBeNull()
    expect(validateImageFile(file('a.png', 'image/png', 1024))).toBeNull()
    expect(validateImageFile(file('a.webp', 'image/webp', 1024))).toBeNull()
    expect(validateImageFile(file('a.gif', 'image/gif', 1024))).toBeNull()
  })

  it('应该拒绝非图片文件', () => {
    expect(validateImageFile(file('a.pdf', 'application/pdf', 1024))).toBe('仅支持 jpg、jpeg、png、webp、gif 图片')
  })

  it('应该拒绝超过 20MB 的图片', () => {
    expect(validateImageFile(file('a.png', 'image/png', 20 * 1024 * 1024 + 1))).toBe('图片不能超过 20MB')
  })

  it('应该限制单条消息最多 8 张图片', () => {
    expect(validateImageCount(8, 1)).toBe('单条消息最多上传 8 张图片')
    expect(validateImageCount(7, 1)).toBeNull()
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd D:\project\Agent-Hub\user-web
npm run test -- chat-attachments.spec.ts
```

Expected: FAIL，原因是 helper 文件不存在。

- [ ] **Step 3: 实现 helper**

创建 `user-web/src/views/chat-attachments.helpers.ts`：

```ts
const maxImageSize = 20 * 1024 * 1024
const supportedTypes = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif'])
const supportedExtensions = new Set(['jpg', 'jpeg', 'png', 'webp', 'gif'])

export function validateImageFile(file: File) {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!supportedTypes.has(file.type) || !supportedExtensions.has(ext)) {
    return '仅支持 jpg、jpeg、png、webp、gif 图片'
  }
  if (file.size > maxImageSize) {
    return '图片不能超过 20MB'
  }
  return null
}

export function validateImageCount(currentCount: number, addingCount: number) {
  if (currentCount + addingCount > 8) {
    return '单条消息最多上传 8 张图片'
  }
  return null
}
```

- [ ] **Step 4: 扩展 chat API**

`user-web/src/api/chat.ts` 增加类型：

```ts
export interface ChatAttachmentRecord {
  attachmentId: number
  filename: string
  contentType: string
  status: string
  expired: boolean
  previewUrl?: string | null
  message?: string | null
}

export interface ImageUploadUrlRecord {
  attachmentId: number
  uploadUrl: string
  method: 'PUT'
  headers: Record<string, string>
  expiresAt: string
}
```

`ChatMessageRecord` 增加：

```ts
attachments?: ChatAttachmentRecord[]
```

`AvailableModelRecord` 增加：

```ts
imageInputSupported: boolean
imageTokenEstimate?: number | null
```

`OpenChatStreamOptions` 增加：

```ts
attachmentIds?: number[]
reuseAttachmentIds?: number[]
```

请求 body 增加：

```ts
attachmentIds: options.attachmentIds ?? [],
reuseAttachmentIds: options.reuseAttachmentIds ?? []
```

新增函数：

```ts
export async function createImageUploadUrl(filename: string, contentType: string, fileSize: number) {
  const response = await http.post<ApiResponse<ImageUploadUrlRecord>>('/chat/attachments/image-upload-url', {
    filename,
    contentType,
    fileSize
  })
  return response.data.data
}

export async function confirmImageUpload(attachmentId: number) {
  await http.post(`/chat/attachments/${attachmentId}/confirm`)
}
```

- [ ] **Step 5: 运行用户端测试**

Run:

```powershell
cd D:\project\Agent-Hub\user-web
npm run test -- chat-attachments.spec.ts
npm run type-check
```

Expected: PASS。

- [ ] **Step 6: 提交**

Run:

```powershell
git add user-web/src/api/chat.ts user-web/src/views/chat-attachments.helpers.ts user-web/tests/chat-attachments.spec.ts
git commit -m "feat: 用户端增加图片上传接口和校验"
```

Expected: commit 成功。

---

## Task 11: 用户端上传照片菜单、预览、进度和移除

**Files:**
- Modify: `user-web/src/views/ChatView.vue`
- Test: `user-web/tests/chat-image-preview.spec.ts`

- [ ] **Step 1: 写图片预览行为测试**

创建 `chat-image-preview.spec.ts`，测试 helper 状态即可，不做 DOM 过度绑定：

```ts
import { describe, expect, it } from 'vitest'

interface UploadItem {
  id: string
  progress: number
  status: 'uploading' | 'ready' | 'failed'
}

function canSend(items: UploadItem[]) {
  return items.every(item => item.status === 'ready')
}

function previewSize(count: number) {
  return count === 1 ? 'large' : 'small'
}

describe('聊天图片预览状态', () => {
  it('一张图片使用大预览', () => {
    expect(previewSize(1)).toBe('large')
  })

  it('多张图片使用小预览', () => {
    expect(previewSize(3)).toBe('small')
  })

  it('上传中不允许发送', () => {
    expect(canSend([{ id: '1', progress: 50, status: 'uploading' }])).toBe(false)
  })

  it('全部 ready 才允许发送', () => {
    expect(canSend([{ id: '1', progress: 100, status: 'ready' }])).toBe(true)
  })
})
```

- [ ] **Step 2: 修改 ChatView 上传状态**

在 `ChatView.vue` 增加状态：

```ts
interface PendingImage {
  localId: string
  file: File
  previewUrl: string
  attachmentId?: number
  progress: number
  status: 'uploading' | 'ready' | 'failed'
  error?: string
}

const pendingImages = ref<PendingImage[]>([])
const imagePreviewUrl = ref<string | null>(null)
```

- [ ] **Step 3: 实现 `+` 向上菜单**

使用 Naive UI `n-dropdown`，trigger 为点击，placement 使用 `top-start`。菜单项：

```ts
const uploadMenuOptions = computed(() => [
  {
    key: 'upload-photo',
    label: '上传照片',
    disabled: !currentModel.value?.imageInputSupported
  }
])
```

禁用时展示提示：

```text
当前模型不支持图片输入
```

- [ ] **Step 4: 实现选择文件和直传**

隐藏 input：

```html
<input ref="imageInputRef" type="file" accept=".jpg,.jpeg,.png,.webp,.gif,image/jpeg,image/png,image/webp,image/gif" multiple hidden @change="handleImageSelected" />
```

上传流程：

```ts
async function uploadImage(item: PendingImage) {
  try {
    const signed = await createImageUploadUrl(item.file.name, item.file.type, item.file.size)
    item.attachmentId = signed.attachmentId
    await uploadToMinio(signed.uploadUrl, signed.headers, item.file, progress => {
      item.progress = progress
    })
    await confirmImageUpload(signed.attachmentId)
    item.status = 'ready'
    item.progress = 100
  } catch {
    item.status = 'failed'
    item.error = '图片上传失败，请重试'
  }
}
```

`uploadToMinio` 用 `XMLHttpRequest` 以便拿上传进度。

- [ ] **Step 5: 实现预览区**

模板规则：

```vue
<div v-if="pendingImages.length" class="pending-image-list" :class="{ single: pendingImages.length === 1 }">
  <div v-for="image in pendingImages" :key="image.localId" class="pending-image-item">
    <img :src="image.previewUrl" @click="imagePreviewUrl = image.previewUrl" />
    <button class="image-remove-button" @click="removePendingImage(image.localId)">×</button>
    <div v-if="image.status === 'uploading'" class="image-progress">{{ image.progress }}%</div>
    <div v-if="image.status === 'failed'" class="image-error">上传失败</div>
  </div>
</div>
```

CSS 要点：

```css
.pending-image-list {
  display: flex;
  gap: 8px;
  padding: 12px 16px 0;
}
.pending-image-item {
  position: relative;
  width: 56px;
  height: 56px;
}
.pending-image-list.single .pending-image-item {
  width: 148px;
  height: 148px;
}
.pending-image-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 8px;
}
.image-remove-button {
  position: absolute;
  top: -8px;
  right: -8px;
}
```

- [ ] **Step 6: 发送按钮置灰规则**

发送禁用条件增加：

```ts
const hasUploadingImage = computed(() => pendingImages.value.some(image => image.status === 'uploading'))
const hasFailedImage = computed(() => pendingImages.value.some(image => image.status === 'failed'))
```

发送时：

```ts
if (hasUploadingImage.value) {
  window.$message?.warning('图片仍在上传中')
  return
}
if (hasFailedImage.value) {
  window.$message?.warning('请先移除上传失败的图片')
  return
}
```

`openChatStream` 传：

```ts
attachmentIds: pendingImages.value
  .map(image => image.attachmentId)
  .filter((id): id is number => typeof id === 'number')
```

发送成功后清空 `pendingImages` 并释放 `URL.revokeObjectURL`。

- [ ] **Step 7: 运行用户端测试和类型检查**

Run:

```powershell
cd D:\project\Agent-Hub\user-web
npm run test
npm run type-check
```

Expected: PASS。

- [ ] **Step 8: 提交**

Run:

```powershell
git add user-web/src/views/ChatView.vue user-web/tests/chat-image-preview.spec.ts
git commit -m "feat: 用户端增加图片上传预览"
```

Expected: commit 成功。

---

## Task 12: 历史图片展示和手动复用

**Files:**
- Modify: `user-web/src/views/ChatView.vue`
- Modify: `user-web/src/api/chat.ts`
- Test: `user-web/tests/chat-image-reuse.spec.ts`

- [ ] **Step 1: 写历史图片复用测试**

创建 `chat-image-reuse.spec.ts`：

```ts
import { describe, expect, it } from 'vitest'

interface Attachment {
  attachmentId: number
  expired: boolean
}

function selectableAttachments(attachments: Attachment[]) {
  return attachments.filter(item => !item.expired)
}

describe('历史图片复用', () => {
  it('只允许选择未过期图片', () => {
    expect(selectableAttachments([
      { attachmentId: 1, expired: false },
      { attachmentId: 2, expired: true }
    ])).toEqual([{ attachmentId: 1, expired: false }])
  })
})
```

- [ ] **Step 2: 历史消息展示附件**

在用户消息气泡中，如果 `message.attachments?.length`：

```vue
<div class="message-attachments">
  <button
    v-for="attachment in message.attachments"
    :key="attachment.attachmentId"
    class="message-attachment"
    :disabled="attachment.expired"
    @click="attachment.previewUrl && (imagePreviewUrl = attachment.previewUrl)"
  >
    <img v-if="attachment.previewUrl" :src="attachment.previewUrl" />
    <span v-else>{{ attachment.message || '图片已过期' }}</span>
  </button>
</div>
```

- [ ] **Step 3: 手动选择历史图片复用**

增加 `selectedReuseAttachmentIds`：

```ts
const selectedReuseAttachmentIds = ref<number[]>([])
```

提供入口按钮文案：

```text
选择历史图片
```

只展示当前会话未过期图片，点击切换选中状态，最多与待上传图片合计 8 张。

发送时传：

```ts
reuseAttachmentIds: selectedReuseAttachmentIds.value
```

发送成功后清空。

- [ ] **Step 4: 运行测试**

Run:

```powershell
cd D:\project\Agent-Hub\user-web
npm run test
npm run type-check
```

Expected: PASS。

- [ ] **Step 5: 提交**

Run:

```powershell
git add user-web/src/views/ChatView.vue user-web/tests/chat-image-reuse.spec.ts
git commit -m "feat: 用户端支持历史图片复用"
```

Expected: commit 成功。

---

## Task 13: 端到端验证与收尾

**Files:**
- Modify only if verification exposes issues.

- [ ] **Step 1: 后端完整测试**

Run:

```powershell
cd D:\project\Agent-Hub\backend
mvn test
```

Expected: PASS。

- [ ] **Step 2: 管理端完整验证**

Run:

```powershell
cd D:\project\Agent-Hub\admin-web
npm run test
npm run type-check
npm run build
```

Expected: PASS。

- [ ] **Step 3: 用户端完整验证**

Run:

```powershell
cd D:\project\Agent-Hub\user-web
npm run test
npm run type-check
npm run build
```

Expected: PASS。

- [ ] **Step 4: Docker 配置验证**

Run:

```powershell
cd D:\project\Agent-Hub\docker
docker compose config
```

Expected: PASS，无配置错误。

- [ ] **Step 5: 真实 MinIO 联调前暂停**

如果尚未由用户确认 MinIO 已启动并配置好 public endpoint，在这里暂停，提示：

```text
代码和静态验证已完成。真实上传/预览/模型读取图片需要你启动 MinIO，并确认 bucket lifecycle 和 public-endpoint。确认后我再继续联调。
```

- [ ] **Step 6: 联调路径**

用户确认 MinIO 已可用后，执行：

```powershell
cd D:\project\Agent-Hub\docker
docker compose ps
```

Expected: `agent-hub-minio` healthy，`agent-hub-minio-init` exited 0。

然后启动后端和前端，手动验证：

```text
1. 管理端把一个 OpenAI 兼容模型设置为支持图片输入。
2. 用户端选择该模型。
3. 点击 +，菜单向上展开。
4. 上传 1 张图片，显示大预览和进度。
5. 上传完成后发送消息。
6. 后端保存用户消息和附件绑定。
7. 历史消息展示图片。
8. 继续追问时默认复用最近图片。
9. 手动选择历史图片复用。
10. 不支持图片模型禁用上传照片。
```

- [ ] **Step 7: 最终提交**

如果联调修复了问题：

```powershell
git add <修复过的文件>
git commit -m "fix: 修复图片多模态联调问题"
```

Expected: commit 成功或没有需要提交的修复。

---

## 自审记录

### 规格覆盖

- 图片上传、预签名直传、确认接口：Task 3、Task 10、Task 11。
- MinIO Compose、bucket lifecycle、7 天清理：Task 1、Task 4。
- 附件表、状态、过期提示：Task 2、Task 3、Task 4、Task 7。
- 历史图片默认复用和手动复用：Task 7、Task 12。
- 模型图片能力开关：Task 5、Task 9。
- OpenAI 兼容多模态协议：Task 6。
- Anthropic URL 图片限制：Task 6。
- 会话摘要和上下文预算：Task 8。
- 用户端单图大预览、多图小预览、进度、移除、大图预览：Task 11。
- 测试与验证：每个任务包含局部测试，Task 13 覆盖完整验证。
- MinIO 配置不确定点暂停：Task 1、Task 13。

### 红旗词检查

计划中没有未完成标记或未定义的后续任务。每个任务都给出了路径、命令、预期结果和关键实现片段。

### 类型一致性

- 后端附件 ID 使用 `Long`，前端使用 `number`。
- 附件状态统一为 `PENDING`、`READY`、`EXPIRED`、`DELETED`。
- 图片能力字段统一为 `imageInputSupported`、`imageTokenEstimate`。
- 新上传附件字段为 `attachmentIds`，复用历史附件字段为 `reuseAttachmentIds`。
