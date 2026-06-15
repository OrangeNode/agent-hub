# Skill 库轻量化重设计

## 背景

上一版 Skill 方案把导入流程设计成多版本、激活版本、sandbox-service 校验和运行时同步。当前产品判断是：首版 Skill 的核心价值不是运行包校验，而是让 Agent 能复用 `SKILL.md` 中的能力说明。因此首版需要收敛复杂度，去掉激活、版本管理和沙箱校验。

## 目标

- 管理端可以上传一个 zip 格式 Skill 包。
- 后端读取 zip 内的 `SKILL.md`，只要存在 `SKILL.md` 就认为 Skill 可导入。
- Skill 名称优先读取 `SKILL.md` frontmatter 的 `name` 字段；没有 `name` 时回退到第一个一级标题；仍没有时回退到上传文件名。
- 上传同名 Skill 时提示管理员选择覆盖或重命名。
- 支持删除 Skill。
- 管理端可以为业务 Agent 绑定一个或多个 Skill，用户端聊天时不允许临时选择这些 Agent 的 Skill。
- 默认/通用 Agent 在 `/chat` 聊天框的 “+” 菜单中支持选择 Skill，支持多选。
- 聊天运行时遵循渐进披露：system prompt 只注入 Skill 名称、说明、ID 和短摘要，完整 `SKILL.md` 通过 `skill.read` 工具按需读取。

## 非目标

- 不做 Skill 激活功能。
- 不做 Skill 版本管理。
- 不把 Skill 上传到 sandbox-service 校验。
- 不把 Skill 文件同步到 sandbox 工作区。
- 不执行 Skill 内的脚本、示例或测试用例。
- 不解析复杂 frontmatter 语义；首版只读取 `name` 和 `description`。

## 方案

新增 `agent-hub-skill` 后端模块。模块负责对象存储上传、zip 读取、`SKILL.md` 提取、同名冲突处理、文件清单保存、Agent 绑定、聊天运行时 Skill 上下文构建和 `skill.read` 工具。

导入流程分成三步：

1. 管理端请求上传 URL，后端创建一条待确认导入记录并返回预签名 PUT URL。
2. 管理端上传 zip 后调用确认接口，后端读取对象存储中的 zip，定位 `SKILL.md`，提取名称、描述、内容和文件清单。
3. 如果名称不冲突，后端直接创建 Skill；如果名称冲突，确认接口返回冲突信息，管理端弹窗选择覆盖或重命名，再调用应用导入接口。

同名判断以最终 Skill 名称为准。覆盖会替换原 Skill 的 zip 元数据、`SKILL.md` 内容和文件清单，但保留原 Skill ID，因此已绑定该 Skill 的 Agent 自动使用新内容。重命名会用管理员输入的新名称创建新的 Skill。

聊天运行时不完整注入 `SKILL.md`。默认/通用 Agent 使用请求中的 `skillIds`；业务 Agent 使用后台绑定 Skill，并忽略前端传入的 `skillIds`。system prompt 只包含 Skill ID、名称、说明和最多 240 字左右的摘要，并提示模型需要完整说明时调用 `skill.read`。`skill.read` 只能读取当前会话已启用 Skill 的完整 `SKILL.md`。当前没有启用 Skill 时，聊天行为保持不变。

## 数据模型

### `skill_package`

保存当前 Skill，不保存历史版本。

字段：`id`、`skill_key`、`name`、`description`、`original_filename`、`bucket_name`、`object_key`、`content_type`、`file_size`、`skill_md_path`、`skill_md_content`、`file_count`、`created_by`、`created_at`、`updated_by`、`updated_at`。

约束：`name` 唯一，`skill_key` 唯一。

### `skill_import`

保存一次待应用导入记录，避免同名冲突时要求管理员重新上传。

字段：`id`、`original_filename`、`bucket_name`、`object_key`、`content_type`、`file_size`、`status`、`parsed_name`、`parsed_description`、`root_dir`、`skill_md_path`、`skill_md_content`、`file_count`、`manifest_json`、`error_message`、`created_by`、`created_at`、`updated_at`。

状态：`UPLOADED`、`PARSED`、`APPLIED`、`FAILED`。

### `skill_file_manifest`

保存 Skill 当前文件清单。

字段：`id`、`skill_id`、`relative_path`、`directory`、`size_bytes`、`sha256`、`created_at`。

### `agent_skill_binding`

保存 Agent 与 Skill 绑定。

字段：`id`、`agent_id`、`skill_id`、`sort`、`created_by`、`created_at`、`updated_by`、`updated_at`。

约束：`agent_id + skill_id` 唯一。

## 接口

```text
GET    /api/skills
GET    /api/skills/available
POST   /api/skills/upload-url
POST   /api/skills/imports/{importId}/confirm
POST   /api/skills/imports/{importId}/apply
GET    /api/skills/{skillId}/files
DELETE /api/skills/{skillId}

GET    /api/agents/{agentId}/skills
PUT    /api/agents/{agentId}/skills
```

`confirm` 返回导入预览。没有冲突时会直接创建 Skill；有冲突时返回 `duplicate=true` 和已有 Skill 信息，不落地覆盖。`apply` 用于冲突后的覆盖或重命名。

## 管理端

新增 `Skill 库` 页面：

- 展示名称、标识、描述、文件数、更新时间。
- 上传 zip。
- 展示同名冲突弹窗，支持覆盖或重命名。
- 展示文件清单。
- 删除 Skill。

Agent 列表增加 `Skill` 绑定入口，可以选择 Skill、移除绑定。保存后该业务 Agent 在聊天时固定使用这些后台绑定 Skill。

## 用户端

`/chat` 聊天框的 “+” 菜单在默认/通用 Agent 下展示 `选择 Skill`，打开选择窗口后可多选 Skill，已选 Skill 以标签显示在聊天框内并可移除。发送消息时前端把选中的 `skillIds` 传给 `/api/chat/stream`。

选择业务 Agent 后，聊天框隐藏 `选择 Skill`，并清空前端已选 Skill。业务 Agent 的 Skill 只来自管理端后台绑定。

## 错误处理

- 非 zip 文件：`仅支持上传 zip 格式 Skill 包`。
- 对象存储文件不存在或上传失败：`Skill 包上传失败，请重新上传`。
- 缺少 `SKILL.md`：`Skill 包缺少 SKILL.md`。
- `SKILL.md` 为空：`SKILL.md 内容不能为空`。
- zip 损坏：`Skill 包不是有效的 zip 文件`。
- 重命名后的名称仍冲突：`Skill 名称已存在`。
- 删除不存在的 Skill：`Skill 不存在`。

## 测试与验收

- 后端单测覆盖 `SKILL.md` 名称解析、缺失文件、同名冲突、覆盖、重命名、删除、Agent 绑定、默认 Agent Skill 选择、业务 Agent 绑定优先级、渐进披露 prompt 和 `skill.read`。
- 管理端测试覆盖 API、路由、上传冲突处理、删除和 Agent 绑定入口。
- 浏览器验收覆盖：上传 Skill、同名覆盖、同名重命名、删除、Agent 绑定、默认 Agent 在 `/chat` 选择多个 Skill、业务 Agent 不能选择 Skill、对话中确认 `skill.read` 可读取完整 `SKILL.md` 且 Skill 指令生效。
