# AI 供应商连通性测试设计

## 目标

在管理端供应商管理列表中增加“测试”操作，用于验证已保存的供应商配置是否能访问上游模型服务，并向管理员展示成功或失败原因。

## 范围

- 后端新增 `POST /api/ai/providers/{providerId}/test-connection`。
- 后端读取已保存供应商配置，使用现有 API Key 密文字段解密占位逻辑获取密钥。
- 测试请求只做轻量模型列表探测，不创建、更新或删除业务数据。
- 前端在供应商列表操作列增加“测试”按钮，调用接口后展示消息提示。

## 协议策略

- `OPENAI_COMPATIBLE`、`OPENAI_CHAT_COMPLETIONS`、`OPENAI_RESPONSES`：请求 `{baseUrl}/v1/models`，若 `baseUrl` 已以 `/v1` 结尾则请求 `{baseUrl}/models`。
- `ANTHROPIC_MESSAGES`：请求 `{baseUrl}/v1/models`，并补充 `x-api-key` 与 `anthropic-version` 请求头。
- HTTP 2xx 视为连通成功；非 2xx 返回失败结果，消息包含状态码。
- 网络异常、超时、缺少 `baseUrl`、缺少 API Key 或不支持协议时返回失败结果，不抛出业务异常给前端。
- 供应商不存在仍按现有统一业务异常返回。

## 返回模型

接口返回统一响应包装内的 `AiProviderConnectionTestResult`：

- `success`：是否连通成功。
- `message`：中文提示。
- `statusCode`：上游 HTTP 状态码，网络异常或本地校验失败时为空。

## 前端交互

列表操作列新增“测试”按钮。点击后按钮进入 loading 状态，接口成功返回时：

- `success=true`：展示成功消息。
- `success=false`：展示错误消息。

测试不影响列表数据，但保留现有刷新、编辑、启停、删除流程。

## 测试

- 后端服务层测试：验证接口会用供应商配置调用连通性测试器并返回结果。
- 后端服务层测试：验证缺少供应商时仍返回统一 `NOT_FOUND` 业务异常。
- 前端静态测试：验证类型、API 方法和供应商列表测试按钮存在。
