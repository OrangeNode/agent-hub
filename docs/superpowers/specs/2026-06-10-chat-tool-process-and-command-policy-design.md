# 聊天工具过程与命令安全校验设计

## 背景

`/chat` 已接入 Agent Runtime Tool Loop，当前前端能接收 `tool_call`、`tool_output` 和 `tool_result`，但工具调用链被展示在助手回复外层，且暴露了 `toolCallId`。用户更关心 Agent 的处理过程和交付结果，不需要看到协议级关联 ID。

同时，用户让 Agent “写一个 python 快速排序算法，读取 input.txt，输出 output.txt，并提供代码和文件下载”时，模型容易选择用 `exec` 写文件或运行脚本。当前命令安全校验对 shell 写文件习惯非常敏感，一旦模型使用重定向、引号、here-doc、shell 包装或越界路径，就会返回“命令未通过安全校验”。这会让正常文件生成任务被误挡。

## 目标

1. 助手消息内提供统一的“处理过程”折叠区，按顺序展示思考片段和工具调用链。
2. `Instant` 和 `Thinking` 模式都默认折叠处理过程。
3. 前端不显示 `toolCallId`，仅保留内部关联。
4. 文件生成类任务优先通过 `write_file` 写入 `/workspace/artifacts`，输入文件通过 `/workspace/input`，降低模型误用 `exec` 的概率。
5. 命令安全校验继续阻止越权路径、shell 注入和危险命令，但允许正常执行 `python3 script.py`、读取 `/workspace/input`、输出 `/workspace/artifacts` 的开发类命令。

## 推荐方案

采用“前端过程事件统一建模 + 后端工具提示增强 + 命令策略补漏”的组合方案。

前端把 `reasoning_delta` 和工具事件统一映射为 `processEvents`。事件类型包含 `reasoning` 和 `tool`，每个工具事件内部仍用 `toolCallId` 合并输出和结果，但 UI 只展示工具名称、状态、stdout、stderr 和结果摘要。助手消息的处理过程只展示在消息内部，默认折叠；用户点击后可以看到“思考 -> 工具调用 -> 思考 -> 工具调用”的流式顺序。

后端继续提供 `exec`、`read_file`、`write_file`、`list_files`、`upload_file`。工具说明和 system prompt 明确告诉模型：用户上传或输入文件在 `/workspace/input`，交付给用户下载的文件必须写入 `/workspace/artifacts`，生成代码和输入输出样例应优先调用 `write_file`，仅在需要执行验证时调用 `exec`。

命令策略保持简单白名单，不引入完整 shell 解释器。允许常见开发命令和 `/workspace` 内参数路径；拒绝 shell 入口、命令连接符、重定向、命令替换、危险系统路径和 `..`。误拦截的主要处理方式不是放宽到任意 shell，而是让模型使用文件工具生成文件，用 `exec` 只运行已经写好的脚本。

## UI 行为

- 助手消息内若存在思考内容或工具事件，显示一个“处理过程”折叠按钮。
- 按钮文本根据状态展示“处理过程”“处理中”“已处理”等简短中文文案。
- `Instant` 与 `Thinking` 模式均默认折叠。
- 展开后按事件顺序展示：
  - 思考片段：合并相邻 `reasoning_delta`，使用轻量文本块展示。
  - 工具调用：展示工具中文名、状态、stdout、stderr、结果摘要。
- 不展示 `toolCallId`。
- 回答正文仍在处理过程下方展示，历史消息未持久化工具过程时只展示现有思考内容和正文。

## 后端行为

- 工具定义说明继续中文化，并强调文件路径规则。
- `systemPrompt` 在工具可用时追加沙箱文件规则和文件交付建议。
- `write_file` 默认路径为 `/workspace/artifacts/output.txt`，相对路径归一到 `/workspace/artifacts`。
- `upload_file` 默认路径为 `/workspace/input/upload.bin`，相对路径归一到 `/workspace/input`。
- `exec` 默认工作目录为 `/workspace`，只执行白名单入口命令。
- 如果模型请求非法命令，仍返回统一错误码 `COMMAND_DENIED` 和中文错误信息。

## 测试策略

- 前端 helper 测试覆盖：工具 ID 只用于合并事件；处理过程默认折叠；工具 ID 不作为展示字段输出；未知工具输出不会丢失。
- SSE 解析测试覆盖：`tool_call`、`tool_output`、`tool_result` 能正确分发。
- 后端聊天测试覆盖：工具可用时 system prompt 包含 `/workspace/input`、`/workspace/artifacts` 和优先使用文件工具的说明。
- 后端命令策略测试覆盖：允许 `python3 quick_sort.py`、`python3 /workspace/artifacts/quick_sort.py`、`cat /workspace/input/input.txt`，拒绝 shell 包装、重定向、越权路径和路径穿越。
- Go sandbox-service 路径策略测试继续覆盖相对路径归一和越界拒绝。
