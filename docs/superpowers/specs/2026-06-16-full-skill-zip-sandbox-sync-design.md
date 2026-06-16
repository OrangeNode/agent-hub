# 完整 Skill Zip 同步到 Sandbox 设计

## 背景

当前 Skill 库采用轻量化运行时：导入 zip 后主要保存 `SKILL.md`，聊天时通过渐进披露和 `skill.read` 让模型读取完整 `SKILL.md`。这种方式适合只有说明文本的 Skill，但不适合 `docx` 这类完整包型 Skill。

`docx` Skill 的 `SKILL.md` 会继续引用 `references/`、`scripts/`、`assets/` 等目录，并要求模型读取参考文件或执行脚本。由于当前运行时不会把完整 Skill 包同步到 sandbox，模型只能读到 `SKILL.md`，读不到引用文件，随后会在 thinking 模式中反复尝试读取不存在的路径或执行被拒绝的命令，最终无法产出文件。

本设计目标是恢复完整 Skill 包在聊天运行时的可用性，同时保留当前轻量化导入体验：Java 后端负责 zip 解析、文件清单、管理端预览；sandbox-service 负责聊天前下载原始 zip、解压到工作区指定目录。

## 目标

- 用户上传完整 Skill zip 后，原始 zip 必须保留在 MinIO。
- Java 后端确认导入时解压 zip，做路径和大小安全校验。
- 解压后的每个普通文件也上传到 MinIO，主要用于管理端文件清单和文本预览。
- 数据库记录原始 zip 元数据、`SKILL.md` 内容、解压文件清单和每个文件的 MinIO objectKey。
- 用户在聊天中勾选 Skill，或业务 Agent 绑定 Skill 后，聊天运行时在调用模型前把完整 Skill zip 同步到 sandbox。
- sandbox-service 下载原始 zip，安全解压到 `/workspace/skills/{skillKey}`。
- 同步成功后才调用 LLM；同步失败通过 SSE `error` 事件明确返回，不进入模型生成。
- system prompt 明确说明完整 Skill 文件所在路径，指导模型通过 sandbox 工具读取引用文件、模板和脚本。

## 非目标

- 不恢复多版本 Skill、激活版本和版本切换。
- 不要求 sandbox-service 在导入阶段校验 zip；导入校验由 Java 后端完成。
- 不把解压后的每个文件逐个通过聊天工具写入 sandbox。
- 不在本设计中扩大命令白名单或安装 `docx` Skill 所需运行时。命令执行能力可以作为后续独立需求处理。
- 不让前端直接访问 MinIO 私有 objectKey；预览仍通过 Java 后端接口读取。

## 总体方案

完整 Skill 包能力分成两条链路。

导入链路：

```text
管理端上传 zip -> MinIO 保存原始 zip -> Java 读取 zip -> 安全解压和解析
-> 保存 Skill 元数据与 SKILL.md -> 解压文件逐个上传 MinIO -> 保存文件清单
```

聊天链路：

```text
用户发起聊天并启用 Skill -> ChatService 解析本轮 Skill
-> 确保 sandbox session 存在 -> 为原始 zip 生成短期预签名下载 URL
-> 调用 sandbox-service 同步接口 -> sandbox 下载 zip 并解压到 /workspace/skills/{skillKey}
-> 同步成功后调用 LLM
```

Java 负责“管理数据”和“发起同步”，sandbox-service 负责“把 zip 变成运行时文件树”。这样管理端预览不依赖 sandbox，聊天运行时也不用逐文件传输大包内容。

## 数据模型调整

### `skill_package`

现有表继续保存 Skill 当前内容，补充或明确以下字段语义：

- `bucket_name`：原始 zip 所在 bucket。
- `object_key`：原始 zip 的 objectKey。
- `content_type`：原始 zip 的内容类型。
- `file_size`：原始 zip 大小。
- `zip_sha256`：原始 zip 哈希，用于运行时同步幂等和审计。
- `skill_md_path`：zip 内 `SKILL.md` 相对路径。
- `skill_md_content`：`SKILL.md` 文本内容。
- `file_count`：解压后纳入清单的文件和目录数量。

如果当前迁移中没有 `zip_sha256`，新增迁移补齐。

### `skill_file_manifest`

文件清单用于管理端展示和预览，增加解压文件对象存储信息：

- `skill_id`：所属 Skill。
- `relative_path`：相对 Skill 根目录的路径，例如 `references/openxml-sdk-reference.md`。
- `directory`：是否目录。
- `size_bytes`：普通文件大小；目录为 0。
- `sha256`：普通文件内容哈希；目录为空。
- `bucket_name`：解压后文件所在 bucket。
- `object_key`：解压后文件的 objectKey；目录为空。
- `content_type`：可选，按文件名推断或使用 `application/octet-stream`。

普通文件的 objectKey 建议格式：

```text
skill-files/{skillId}/{zipSha256}/{relativePath}
```

路径中的 `relativePath` 需要先安全归一化，再做 URL/对象 key 安全编码或分段拼接，避免对象名冲突和路径歧义。

## Zip 解析与安全校验

Java 后端确认导入时读取原始 zip 字节并解析，要求：

- 必须存在一个 `SKILL.md`。
- 支持 `SKILL.md` 位于 zip 根目录，或位于唯一 Skill 根目录下。
- 所有路径统一转为 `/` 分隔。
- 禁止空路径、绝对路径、Windows 盘符路径、`.`、`..`、路径穿越和重复归一化路径。
- 禁止符号链接、设备文件和非常规文件类型。
- 限制 zip 大小、解压后总大小、单文件大小、文件数量和目录深度。
- `SKILL.md` 必须是 UTF-8 文本，且不能为空。

解析成功后，Java 同时完成两件事：

- 把 `SKILL.md` 内容写入 `skill_package.skill_md_content`，供 `skill.read` 使用。
- 把每个普通文件上传到 MinIO，并写入 `skill_file_manifest.object_key`，供管理端预览使用。

## 管理端预览

管理端文件清单仍通过 Java 接口读取：

```text
GET /api/skills/{skillId}/files
GET /api/skills/{skillId}/files/content?path=...
```

预览接口不再从原始 zip 内按需查找文件，而是根据 `skill_file_manifest.object_key` 直接读取解压后的单文件对象。

预览规则：

- 目录不能预览。
- 文件超过预览上限时返回“文件超过 512 KB，暂不支持在线预览”。
- 二进制文件返回“该文件不是文本文件，暂不支持预览”。
- 文本文件按 UTF-8 返回内容。

## Sandbox 同步接口

新增 sandbox-service 内部接口：

```text
POST /internal/sessions/{sessionId}/skills/sync-zip
```

请求体：

```json
{
  "skills": [
    {
      "skillId": 6,
      "skillKey": "docx",
      "zipDownloadUrl": "https://minio.example/presigned",
      "zipSha256": "0123456789abcdef",
      "targetDir": "/workspace/skills/docx"
    }
  ]
}
```

响应体：

```json
{
  "success": true,
  "skills": [
    {
      "skillId": 6,
      "skillKey": "docx",
      "targetDir": "/workspace/skills/docx",
      "synced": true,
      "message": "同步完成"
    }
  ]
}
```

失败响应：

```json
{
  "success": false,
  "skills": [
    {
      "skillId": 6,
      "skillKey": "docx",
      "targetDir": "/workspace/skills/docx",
      "synced": false,
      "message": "Skill 包不是有效的 zip 文件"
    }
  ]
}
```

### 同步规则

- `targetDir` 必须位于 `/workspace/skills/{skillKey}`，不能由模型控制。
- sandbox-service 下载 zip 到临时目录。
- 解压前删除旧的 `/workspace/skills/{skillKey}` 目录。
- 安全解压 zip 内容到目标目录。
- 如果 zip 内存在单一根目录，目标目录下直接放 Skill 内容，不额外嵌套根目录。
- 解压完成后写入同步标记文件：

```text
/workspace/skills/{skillKey}/.agenthub-skill.json
```

内容包含 `skillId`、`skillKey`、`zipSha256` 和同步时间。

- 如果同一 sandbox session 中目标目录已存在，且同步标记的 `zipSha256` 与请求一致，sandbox-service 可以跳过重复下载；但跳过前必须确认目标目录存在。
- 多个 Skill 同步时互不影响，一个 Skill 失败则整个同步请求返回失败，ChatService 不调用 LLM。

## ChatService 集成

聊天准备阶段现有流程会构造 SkillRuntimeContext。新增同步步骤：

1. 解析本轮启用的 Skill IDs。
2. 查询每个 Skill 的 `skillKey`、原始 zip `objectKey`、`zipSha256`。
3. 生成每个 zip 的短期 MinIO 预签名下载 URL。
4. 确保当前会话有 sandbox session。
5. 调用 sandbox-service `sync-zip`。
6. 同步失败时标记助手消息失败，并通过 SSE 发送错误事件。
7. 同步成功后再构造 LLM 请求并开始流式调用。

system prompt 的 Skill 渐进披露内容需要增加路径说明：

```text
已启用以下 Skill。完整文件已同步到 sandbox：

[Skill]
Skill ID：6
名称：docx
路径：/workspace/skills/docx
说明：Create and edit Word documents (.docx)
摘要：...
完整说明：调用 skill.read，参数 skill_id=6。
如需读取 references、scripts、assets 或 examples，请使用 sandbox 工具访问上述路径。
```

## 错误处理

导入阶段：

- zip 损坏：`Skill 包不是有效的 zip 文件`
- 缺少 `SKILL.md`：`Skill 包缺少 SKILL.md`
- `SKILL.md` 为空：`SKILL.md 内容不能为空`
- 路径不安全：`Skill 包包含不安全路径`
- 超过大小或文件数量限制：返回包含具体限制的中文错误
- 解压文件上传 MinIO 失败：`Skill 文件保存失败，请重新上传`

聊天阶段：

- Skill 不存在：忽略无效 ID 或按现有策略报错；默认 Agent 前端选择建议报错更明确。
- Skill zip object 不存在：`Skill 包文件不存在，请重新上传`
- sandbox-service 不可用：`Skill 同步到沙箱失败：沙箱服务不可用`
- zip 下载失败：`Skill 同步到沙箱失败：下载 Skill 包失败`
- zip 解压失败：`Skill 同步到沙箱失败：Skill 包解压失败`

所有聊天阶段同步失败都通过 SSE `error` 事件返回，不继续调用模型。

## 安全边界

- 前端不直接访问 MinIO。
- 聊天运行时只向 sandbox-service 传短期预签名下载 URL。
- sandbox-service 不信任 zip 内容，必须重复做路径安全校验。
- `/workspace/skills/{skillKey}` 只由后端根据数据库中的 `skillKey` 生成，不能由用户 prompt 或模型工具参数决定。
- 解压时必须保证最终路径仍位于目标目录内。
- 解压前清理目标目录时必须确认目标目录位于 `/workspace/skills` 下。

## 测试计划

### 后端 Java

- `SkillArchiveParserTest` 覆盖根目录 `SKILL.md`、单一根目录 `SKILL.md`、缺失 `SKILL.md`、zip slip、重复归一化路径、超限文件。
- `SkillPackageServiceImplTest` 覆盖导入时保存原始 zip 元数据、解压文件上传 MinIO、文件清单写入 objectKey、覆盖同名 Skill 时替换文件清单。
- `SkillPackageServiceImplTest` 覆盖管理端预览从解压文件 objectKey 读取，而不是从原始 zip 读取。
- `SkillRuntimeServiceTest` 覆盖 prompt 包含 `/workspace/skills/{skillKey}` 路径说明。
- `ChatServiceImplTest` 覆盖调用 LLM 前触发 Skill zip 同步。
- `ChatServiceImplTest` 覆盖同步失败时发送 SSE 错误且不调用 LLM。

### sandbox-service

- 正常 zip 解压到 `/workspace/skills/{skillKey}`。
- 单一根目录 zip 解压后不产生额外嵌套目录。
- zip slip、绝对路径、Windows 盘符路径被拒绝。
- 解压前清理旧目录。
- `.agenthub-skill.json` hash 一致时可跳过重复同步。
- 多 Skill 同步中任一失败时返回整体失败。

### 用户端与管理端

- 管理端 Skill 文件清单展示解压后的完整文件列表。
- 管理端文本预览能读取 `references/openxml-sdk-reference.md`。
- 用户端默认 Agent 勾选 `docx` 后发起聊天，请求中包含 `skillIds`。
- 后端日志或测试替身确认 sandbox 中出现 `/workspace/skills/docx/SKILL.md` 和 `references/`。

## 验收标准

- 上传 `docx` 完整 Skill zip 后，管理端可以看到 `SKILL.md`、`references/`、`scripts/` 等完整清单。
- 管理端能预览文本型引用文件。
- 用户在聊天中勾选 `docx` 后，本轮模型调用前 sandbox 内存在 `/workspace/skills/docx` 完整目录。
- 如果 Skill 同步失败，前端收到明确错误，不再出现长时间 thinking 循环。
- 未选择 Skill 的聊天行为不受影响。
