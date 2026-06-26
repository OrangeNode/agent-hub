# 自动 Plan 与任务进度状态设计

## 背景

当前聊天链路已经支持 SSE 流式输出、思考内容、工具调用过程、沙箱产物和 Skill 注入。后端在执行时会列出当前 Agent 可用工具，并把工具交给模型决定是否调用。

下一阶段需要让 Agent 对复杂任务自动进入 Plan 执行，不要求用户显式开启。用户在同一个对话中可能持续追加任务或修改要求，因此 Plan 不能每轮粗暴重置，也不能过早引入完整版本管理导致状态模型臃肿。

本设计把 Plan 定义为 Agent Runtime 的自动运行策略，把任务进度定义为可恢复的运行状态，把长期记忆与本轮任务状态拆开。

## 目标

- 默认自动判断本轮是否需要 Plan，不在用户端暴露“Plan 模式”开关。
- 普通闲聊直接回复，不显示任务进度。
- 复杂任务自动创建任务运行状态，并在用户端输入框上方显示进度。
- 支持用户在同一对话中连续推进任务，不因每轮消息都重置当前任务。
- 使用轻量状态模型保存当前计划和步骤进度，不做完整计划版本管理。
- 保留过程事件用于历史回放和审计线索，但不把过程事件作为运行中进度的主状态源。

## 非目标

- 不实现长期记忆系统。用户偏好、跨会话事实和长期画像后续单独设计。
- 不实现计划版本差异查看、旧计划恢复或计划分支。
- 不要求前端展示百分比进度。首版只展示当前步骤和总步骤。
- 不改变普通 REST 接口统一响应规范。
- 不重做现有 Tool Loop、Skill 同步、沙箱产物和 SSE 基础协议。

## 运行路由

每轮用户消息进入聊天运行时后，先由 `AgentRunRouter` 判断运行方式。

```text
DIRECT_CHAT
普通闲聊、解释、翻译、简单问答。不注入工具，不创建任务进度。

TOOL_ASSISTED
需要调用一个或少量工具，但任务本身不需要拆成正式计划。可展示轻量状态，也可只展示现有工具过程。

PLAN_EXECUTE
多步骤、文件生成、代码修改、数据处理、复杂查询、产物交付、跨工具协作、需要先拆解再执行的任务。
```

首版路由结果可以是内部 DTO：

```java
public record AgentRunDecision(
        RunMode mode,
        ToolPolicy toolPolicy,
        PlanPolicy planPolicy,
        String reason
) {
}
```

`thinkingEnabled` 只表示是否请求模型输出思考内容，不等同于 Plan。Plan 是运行策略，Thinking 是模型输出能力。

## 任务边界

一个用户消息默认触发一次运行判断，但不一定创建新的任务。

```text
新任务：创建新的 agent_task_run。
延续当前任务：复用当前 active task_run，追加或修订步骤。
普通闲聊：不创建 task_run。
```

延续当前任务的典型表达：

- “继续”
- “刚才那个再加一个导出功能”
- “把第 3 步改一下”
- “前面生成的报告再补充图表”
- “继续跑测试”

新任务的典型表达：

- “再帮我写一个登录页”
- “新建一个数据分析脚本”
- “换个任务，帮我整理会议纪要”

后端根据最近 active task、用户消息、会话上下文和运行结果判断是否复用。判断不确定时，优先新建任务，避免把不相关目标混在同一个进度中。

## 状态存储

首版新增两张表保存当前任务状态。

```text
agent_task_run
- id
- user_id
- conversation_id
- assistant_message_id
- parent_task_run_id
- mode
- status
- title
- plan_summary
- current_step_index
- total_steps
- revision
- error_message
- created_at
- updated_at
- completed_at

agent_task_step
- id
- task_run_id
- step_index
- name
- status
- error_message
- started_at
- completed_at
```

`parent_task_run_id` 用于表达“这是前一个任务的后续任务”，不是计划版本。连续任务可以形成轻量链路，但前端默认只展示最新 active 任务。

`revision` 用于标识当前计划被修订过，支持前端刷新和服务端乐观更新。首版不保存每个 revision 的完整历史。

## 状态枚举

`agent_task_run.status`：

```text
PLANNING
RUNNING
COMPLETED
FAILED
STOPPED
```

`agent_task_step.status`：

```text
PENDING
RUNNING
COMPLETED
FAILED
SKIPPED
```

消息停止生成时，如果存在 active task_run，应同步更新为 `STOPPED`。模型或工具失败导致本轮无法完成时，更新为 `FAILED`。

## 计划修订

不做完整 `plan_version` 表。计划调整时直接更新当前 `agent_task_step`，并让 `agent_task_run.revision + 1`。

同时在 `chat_message.process_events` 追加一条过程事件：

```json
{
  "type": "task_plan_revised",
  "taskRunId": 123,
  "revision": 2,
  "summary": "根据用户补充要求，新增导出文件步骤。"
}
```

这样可以满足前端刷新和历史回放，又不会引入沉重的版本管理模型。

后续只有出现以下需求时，才升级为完整版本管理：

- 用户需要查看每次计划变更差异。
- 用户需要恢复到旧计划。
- 管理员需要审计 Agent 为什么改计划。
- 多人协作需要比较不同计划分支。

## 前端展示

任务进度显示在聊天输入框上方。

折叠态只显示一行：

```text
任务进度：正在执行 2 / 5
```

文案规则：

```text
准备中：任务进度：准备中
进行中：任务进度：正在执行 2 / 5
完成：任务进度：已完成 5 / 5
失败：任务进度：执行失败 2 / 5
停止：任务进度：已停止 2 / 5
```

点击展开后显示任务名称和任务进度：

```text
任务名称              任务进度
分析需求              已完成
读取项目文件          已完成
修改后端路由          进行中
补充前端进度展示      等待中
运行测试              等待中
```

图标规则：

```text
RUNNING：加载中图标
COMPLETED：绿色圆点
PENDING：灰色圆点
FAILED：红色圆点
SKIPPED：黄色圆点
```

普通 `DIRECT_CHAT` 不显示任务进度。`TOOL_ASSISTED` 首版可以沿用现有工具过程展示，不强制创建完整任务进度。`PLAN_EXECUTE` 必须显示任务进度。

## SSE 事件

在现有聊天 SSE 协议上追加任务进度事件。前端必须忽略未知事件，保证兼容。

```text
task_progress_started
task_progress_updated
task_step_started
task_step_completed
task_step_failed
task_plan_revised
task_progress_completed
```

`task_progress_updated` 示例：

```json
{
  "taskRunId": 123,
  "status": "RUNNING",
  "currentStepIndex": 2,
  "totalSteps": 5,
  "revision": 1,
  "steps": [
    {
      "stepIndex": 1,
      "name": "分析需求",
      "status": "COMPLETED"
    },
    {
      "stepIndex": 2,
      "name": "读取项目文件",
      "status": "RUNNING"
    }
  ]
}
```

前端以 SSE 事件实时更新当前任务状态。刷新页面后，前端通过历史消息或任务查询接口恢复最新 active task。

## 历史恢复

首版只要求恢复“最新 active task”和“历史消息最终过程”。

- 当前对话有 `PLANNING` 或 `RUNNING` 的 task_run 时，输入框上方显示该任务进度。
- 当前对话没有 active task_run 时，不显示进度条。
- 历史消息中保留 `process_events`，用于展示已完成消息的过程摘要。

不恢复已经断开的 SSE 流本身。刷新后以前端查询到的服务端状态为准。

## 与 process_events 的边界

`agent_task_run` 和 `agent_task_step` 是运行中任务状态的主数据源。

`chat_message.process_events` 保存过程回放，包括：

- 任务计划创建。
- 任务计划修订。
- 工具调用。
- 工具输出。
- 工具结果。

不要只依赖 `process_events` 驱动输入框上方的进度展示，因为它是追加日志，不适合表达当前状态、步骤更新、停止生成和刷新恢复。

## 测试范围

后端测试：

- 普通闲聊路由到 `DIRECT_CHAT`，不创建 task_run。
- 复杂任务路由到 `PLAN_EXECUTE`，创建 task_run 和 task_step。
- 延续当前任务时复用 active task_run 并递增 revision。
- 新任务在同一对话中创建新的 task_run。
- 停止生成时 active task_run 更新为 `STOPPED`。
- 计划修订时更新步骤并追加 `task_plan_revised` 过程事件。

前端测试：

- 无任务时输入框上方不显示进度。
- 折叠态显示“任务进度：正在执行 2 / 5”。
- 点击展开后显示任务名称和状态图标。
- 进行中步骤使用加载中图标，已完成步骤使用绿色圆点。
- 失败和停止状态显示对应文案。
- 收到 `task_plan_revised` 后列表更新。

## 实施顺序

1. 新增任务状态表和后端 DTO。
2. 在聊天运行时增加 `AgentRunRouter` 和 `RunMode`。
3. 为 `PLAN_EXECUTE` 创建 task_run 和 task_step。
4. 扩展 SSE 事件模型并发送任务进度事件。
5. 前端聊天页输入框上方新增任务进度折叠组件。
6. 补充后端服务测试和前端交互测试。
