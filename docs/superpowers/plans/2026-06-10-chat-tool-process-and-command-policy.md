# Chat Tool Process And Command Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `/chat` 工具调用链收进默认折叠的处理过程，并减少正常文件生成任务被命令安全校验误拦截。

**Architecture:** 前端新增处理过程事件模型，把思考片段和工具事件统一渲染在助手消息内部。后端增强工具提示与路径默认值，命令策略保持白名单和路径边界，文件生成优先使用 `write_file`，命令执行只用于运行已生成脚本。

**Tech Stack:** Vue 3、TypeScript、Vitest、Java 21、Spring Boot、JUnit 5、Go 1.22。

---

### Task 1: 前端处理过程模型

**Files:**
- Modify: `user-web/src/views/chat-tool-events.helpers.ts`
- Modify: `user-web/tests/chat-tool-events.spec.ts`

- [ ] **Step 1: 写失败测试**

在 `user-web/tests/chat-tool-events.spec.ts` 覆盖两类行为：

```ts
it('应该把工具调用折叠进处理过程且不暴露展示用 ID', () => {
  const events = createProcessEvents()

  appendReasoningEvent(events, '先写入代码。')
  applyToolCallEvent(events, { toolCallId: 'call_1', name: 'write_file' })
  applyToolOutputEvent(events, { toolCallId: 'call_1', stream: 'stdout', content: '写入中' })
  applyToolResultEvent(events, {
    toolCallId: 'call_1',
    name: 'write_file',
    status: 'completed',
    content: '文件写入完成'
  })

  expect(events).toMatchObject([
    { type: 'reasoning', content: '先写入代码。' },
    {
      type: 'tool',
      tool: {
        name: 'write_file',
        status: 'completed',
        stdout: '写入中',
        stderr: '',
        result: '文件写入完成'
      }
    }
  ])
  expect('displayId' in (events[1] as any).tool).toBe(false)
})
```

- [ ] **Step 2: 运行前端单测确认失败**

Run: `cd user-web && npm test -- chat-tool-events.spec.ts`

Expected: FAIL，提示 `createProcessEvents` 或 `appendReasoningEvent` 不存在。

- [ ] **Step 3: 实现最小 helper**

在 helper 中定义 `ChatProcessEvent`、`ChatToolTrace`、`createProcessEvents`、`appendReasoningEvent`，并让工具事件通过内部 `toolCallId` 找到对应 tool 事件。

- [ ] **Step 4: 运行前端单测确认通过**

Run: `cd user-web && npm test -- chat-tool-events.spec.ts`

Expected: PASS。

### Task 2: 前端消息内折叠展示

**Files:**
- Modify: `user-web/src/views/ChatView.vue`

- [ ] **Step 1: 修改消息状态**

将助手消息的 `toolTraces` 改为 `processEvents`，并新增 `processExpanded: false`。`reasoning_delta` 到达时追加到 `processEvents`，工具事件到达时合并到同一数组。

- [ ] **Step 2: 修改模板**

用单个 `.process-block` 取代独立 `reasoning-block` 和外置 `tool-trace-list`。按钮默认折叠，展开后渲染思考片段和工具调用。模板中不渲染 `toolCallId`。

- [ ] **Step 3: 修改样式**

保留现有聊天风格，新增 `.process-block`、`.process-toggle`、`.process-content`、`.process-tool`。固定间距和宽度，避免展开时文字溢出。

- [ ] **Step 4: 运行前端构建检查**

Run: `cd user-web && npm run type-check`

Expected: PASS。

### Task 3: 后端工具提示与命令策略

**Files:**
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/policy/CommandPolicy.java`
- Modify: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/policy/CommandPolicyTest.java`

- [ ] **Step 1: 写命令策略回归测试**

新增允许用例：

```java
assertThatCode(() -> policy.validate("python3 quick_sort.py", "/workspace", 30))
        .doesNotThrowAnyException();
assertThatCode(() -> policy.validate("python3 /workspace/artifacts/quick_sort.py", "/workspace", 30))
        .doesNotThrowAnyException();
assertThatCode(() -> policy.validate("cat /workspace/input/input.txt", "/workspace", 30))
        .doesNotThrowAnyException();
```

新增拒绝用例：

```java
assertThatThrownBy(() -> policy.validate("python3 -c \"print(1)\"", "/workspace", 30))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.COMMAND_DENIED);
```

- [ ] **Step 2: 写聊天提示回归测试**

确认工具可用时 system prompt 同时包含 `/workspace/input`、`/workspace/artifacts`、`write_file` 和“下载”相关语义。

- [ ] **Step 3: 实现后端提示增强**

更新 `SANDBOX_PATH_RULES`，明确“生成给用户下载的代码、input、output 文件时优先调用 write_file 写入 /workspace/artifacts；exec 只用于运行脚本验证”。

- [ ] **Step 4: 实现命令策略补漏**

保留禁止 shell 语法，允许 `/workspace` 内绝对路径参数；若入口命令不是白名单或参数路径越界，继续抛 `COMMAND_DENIED`。

- [ ] **Step 5: 运行后端目标测试**

Run: `cd backend && mvn -pl agent-hub-chat,agent-hub-sandbox -am test`

Expected: PASS。

### Task 4: 验证与收尾

**Files:**
- Read: `git diff`

- [ ] **Step 1: 运行前端测试**

Run: `cd user-web && npm test -- chat-tool-events.spec.ts chat-stream-tool-events.spec.ts`

Expected: PASS。

- [ ] **Step 2: 运行类型检查**

Run: `cd user-web && npm run type-check`

Expected: PASS。

- [ ] **Step 3: 运行后端测试**

Run: `cd backend && mvn -pl agent-hub-chat,agent-hub-sandbox -am test`

Expected: PASS。

- [ ] **Step 4: 检查 diff**

Run: `git diff --stat`

Expected: 只包含聊天工具过程、命令策略和对应文档/测试改动。
