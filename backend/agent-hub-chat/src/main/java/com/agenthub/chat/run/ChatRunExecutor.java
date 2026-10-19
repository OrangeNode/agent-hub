package com.agenthub.chat.run;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmClientFactory;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamCancelledException;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamChunkType;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.llm.tool.LlmToolCall;
import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.chat.message.support.AssistantMessageStore;
import com.agenthub.chat.message.support.AssistantReply;
import com.agenthub.chat.task.runtime.ChatTaskProgressTracker;
import com.agenthub.chat.task.runtime.ToolPolicy;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sse.SseEventData;
import com.agenthub.sse.SseEventType;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import com.agenthub.tool.runtime.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Agent 运行循环：调用模型 → 执行工具 → 回填结果 → 再次调用模型，直到模型给出最终回复。
 * 运行期间通过 {@link ChatRunHandle} 协作式取消，并保证助手消息终态只写入一次。
 */
@Component
public class ChatRunExecutor {

    private static final String ERROR_SOURCE_LLM = "LLM";
    private static final String ERROR_SOURCE_TOOL = "TOOL";
    private static final String ERROR_SOURCE_SERVER = "SERVER";
    private static final String LLM_STREAM_FAILED = "LLM_STREAM_FAILED";
    private static final String TOOL_LOOP_FAILED = "TOOL_LOOP_FAILED";
    private static final String RUN_ABORTED = "RUN_ABORTED";
    private static final String CONTEXT_LENGTH_RETRY_FAILED_MESSAGE =
            "模型上下文长度仍然超限，请缩短输入或切换更大上下文模型";
    private static final String SANDBOX_PATH_RULES = """
            沙箱文件规则：
            - 工具路径必须位于允许目录：/workspace 或 /tmp；不要访问其他绝对路径。
            - 用户上传或导入的文件位于 /workspace/input，请从这里读取输入材料。
            - 需要交付给用户的输出文件必须写入 /workspace/artifacts，例如 /workspace/artifacts/作文.txt。
            - /tmp 仅用于构建、编译、缓存等临时工作目录，例如 /tmp/docx-work；最终交付文件不要留在 /tmp。
            - 用户要求下载代码、input、output 或其他文件时，优先调用 sandbox.write_file 逐个写入 /workspace/artifacts，文件名要清晰可识别。
            - 不要在最终回复中把 /workspace/artifacts/...、/workspace/input/... 或其他沙箱内部路径写成 Markdown 下载链接或 URL；系统会根据产物记录展示“全部文件”卡片和下载按钮，你只需说明文件已生成、可在文件卡片中预览或下载。
            - sandbox.exec 可以用于复杂任务编排、脚本执行、重定向、管道和 here-doc；所有需要用户下载的最终文件仍应写入 /workspace/artifacts。
            - 如果工具参数使用相对路径，服务端会按工具类型统一归一到 /workspace/input 或 /workspace/artifacts；不要尝试使用 ..、/etc、/root 等越界路径。
            """;

    private final Function<String, LlmClient> llmClientFactory;
    private final ToolRegistry toolRegistry;
    private final AssistantMessageStore messageStore;
    private final ChatTaskProgressTracker taskTracker;
    private final SkillSandboxSynchronizer skillSynchronizer;
    private final ChatRunProperties properties;

    @Autowired
    public ChatRunExecutor(
            ToolRegistry toolRegistry,
            AssistantMessageStore messageStore,
            ChatTaskProgressTracker taskTracker,
            SkillSandboxSynchronizer skillSynchronizer,
            ChatRunProperties properties
    ) {
        this(LlmClientFactory::create, toolRegistry, messageStore, taskTracker, skillSynchronizer, properties);
    }

    public ChatRunExecutor(
            Function<String, LlmClient> llmClientFactory,
            ToolRegistry toolRegistry,
            AssistantMessageStore messageStore,
            ChatTaskProgressTracker taskTracker,
            SkillSandboxSynchronizer skillSynchronizer,
            ChatRunProperties properties
    ) {
        this.llmClientFactory = llmClientFactory;
        this.toolRegistry = toolRegistry;
        this.messageStore = messageStore;
        this.taskTracker = taskTracker;
        this.skillSynchronizer = skillSynchronizer;
        this.properties = properties;
    }

    public void execute(ChatRunRequest request, ChatRunHandle handle, RunEventSink events) {
        new Run(request, handle, events).execute();
    }

    private final class Run {

        private final ChatRunRequest request;
        private final ChatRunHandle handle;
        private final RunEventSink events;
        private final AssistantReply reply;
        private final AtomicBoolean skillZipsSynced = new AtomicBoolean(false);
        private final boolean reasoningRequested;
        private final ToolExecutionContext toolContext;

        private Run(ChatRunRequest request, ChatRunHandle handle, RunEventSink events) {
            this.request = request;
            this.handle = handle;
            this.events = events;
            this.reply = new AssistantReply(request.assistantMessage());
            // 只有用户开启思考且模型声明支持时，才向前端透出推理片段。
            this.reasoningRequested = request.thinkingEnabled() && request.runtimeConfig().reasoningSupported();
            this.toolContext = new ToolExecutionContext(
                    request.userId(),
                    request.conversationId(),
                    reply.messageId(),
                    request.agentKey(),
                    request.agentId(),
                    request.skillContext() == null ? List.of() : request.skillContext().skillIds(),
                    this::onToolOutput
            );
        }

        private void execute() {
            String errorSource = ERROR_SOURCE_SERVER;
            List<LlmToolDefinition> availableTools = List.of();
            List<LlmMessage> loopMessages = new ArrayList<>(request.messages());
            try {
                taskTracker.start(events, request.runtimeConfig(), request.prompt(), request.taskRunContext());
                errorSource = ERROR_SOURCE_TOOL;
                availableTools = shouldExposeTools() ? listTools() : List.of();
                errorSource = ERROR_SOURCE_LLM;
                LlmClient client = llmClientFactory.apply(request.runtimeConfig().protocolType());
                loopMessages = withSandboxPathRules(request.messages(), availableTools);
                int toolCallCount = 0;
                while (!reply.isFinished()) {
                    checkpoint();
                    errorSource = ERROR_SOURCE_LLM;
                    StreamAttemptOutcome outcome = streamOnce(client, loopMessages, availableTools);
                    if (outcome.retryRequested() && !reply.isFinished()) {
                        outcome = streamOnce(client, compactMessagesForRetry(loopMessages), availableTools);
                        if (outcome.retryRequested() && reply.tryFinish()) {
                            failAndSendError(CONTEXT_LENGTH_RETRY_FAILED_MESSAGE, ERROR_SOURCE_LLM);
                        }
                    }
                    if (reply.isFinished()) {
                        return;
                    }
                    if (outcome.toolCalls().isEmpty()) {
                        if (reply.tryFinish()) {
                            if (outcome.doneSeen()) {
                                complete();
                            } else {
                                failAndSendError("模型流式响应未正常结束", ERROR_SOURCE_LLM);
                            }
                        }
                        return;
                    }
                    errorSource = ERROR_SOURCE_TOOL;
                    if (availableTools.isEmpty()) {
                        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "工具服务不可用");
                    }
                    if (toolCallCount + outcome.toolCalls().size() > properties.getMaxToolCalls()) {
                        if (reply.tryFinish()) {
                            failAndSendError("工具调用次数超过上限", ERROR_SOURCE_TOOL);
                        }
                        return;
                    }
                    toolCallCount += outcome.toolCalls().size();
                    loopMessages.add(LlmMessage.assistantToolCalls(outcome.toolCalls()));
                    for (LlmToolCall toolCall : outcome.toolCalls()) {
                        if (reply.isFinished()) {
                            return;
                        }
                        checkpoint();
                        String result = executeToolCall(toolCall);
                        if (reply.isFinished()) {
                            return;
                        }
                        loopMessages.add(LlmMessage.tool(toolCall.id(), toolCall.name(), result));
                    }
                }
            } catch (LlmStreamCancelledException cancelled) {
                applyCancellation();
            } catch (Exception exception) {
                if (handle.isCancelled()) {
                    // 工具实现可能把取消信号包装成普通异常，这里统一按取消处理。
                    applyCancellation();
                } else if (!reply.hasOutput() && isContextLengthError(exception.getMessage())) {
                    retryAfterContextLengthException(loopMessages, availableTools, exception);
                } else if (reply.tryFinish()) {
                    failAndSendError(exception.getMessage(), errorSource);
                }
            } finally {
                taskTracker.finish(events, reply.message(), request.taskRunContext());
                events.send(SseEventType.DONE, new SseEventData.Done());
                events.complete();
            }
        }

        /**
         * 取消检查点：超时、本实例中止信号、其他实例写入数据库的中止状态都会在这里生效。
         */
        private void checkpoint() {
            if (!handle.isCancelled()) {
                if (handle.isExpired()) {
                    handle.cancel(RunCancelReason.TIMEOUT);
                } else if (handle.shouldPollStop() && messageStore.isStopRequested(reply.messageId())) {
                    handle.cancel(RunCancelReason.USER_STOPPED);
                }
            }
            RunCancelReason reason = handle.cancelReason();
            if (reason != null) {
                throw new LlmStreamCancelledException(reason.message());
            }
        }

        private void applyCancellation() {
            RunCancelReason reason = handle.cancelReason();
            if (reason == null || !reply.tryFinish()) {
                return;
            }
            if (reason.failure()) {
                failAndSendError(reason.message(), ERROR_SOURCE_SERVER);
            } else {
                messageStore.markStopped(reply, reason.message());
            }
        }

        /**
         * 发送失败说明客户端已断开：立即以 STOPPED 落库已生成内容，并让后续检查点终止运行。
         */
        private boolean sendOrStop(SseEventType eventType, SseEventData data) {
            if (events.send(eventType, data)) {
                return true;
            }
            handle.cancel(RunCancelReason.CLIENT_DISCONNECTED);
            if (reply.tryFinish()) {
                messageStore.markStopped(reply, RunCancelReason.CLIENT_DISCONNECTED.message());
            }
            return false;
        }

        private void onToolOutput(String toolCallId, String toolName, String stream, String output) {
            String safeStream = hasText(stream) ? stream : "stdout";
            String safeOutput = defaultString(output);
            reply.processRecorder().appendToolOutput(toolCallId, toolName, safeStream, safeOutput);
            sendOrStop(SseEventType.TOOL_OUTPUT, new SseEventData.ToolOutput(toolCallId, safeStream, safeOutput));
            checkpoint();
        }

        private boolean shouldExposeTools() {
            if (request.skillContext() != null && !request.skillContext().skillIds().isEmpty()) {
                return true;
            }
            if (request.hasAttachments()) {
                return true;
            }
            return request.runDecision() != null && request.runDecision().toolPolicy() == ToolPolicy.AUTO;
        }

        private List<LlmToolDefinition> listTools() {
            if (toolRegistry == null) {
                return List.of();
            }
            return toolRegistry.listTools(toolContext);
        }

        private StreamAttemptOutcome streamOnce(
                LlmClient client,
                List<LlmMessage> messages,
                List<LlmToolDefinition> availableTools
        ) {
            AtomicBoolean retryRequested = new AtomicBoolean(false);
            AtomicBoolean sawDelta = new AtomicBoolean(false);
            AtomicBoolean doneSeen = new AtomicBoolean(false);
            List<LlmToolCall> toolCalls = new ArrayList<>();
            client.stream(buildRequest(messages, availableTools), chunk -> {
                checkpoint();
                if (retryRequested.get()) {
                    return;
                }
                if (chunk.type() == LlmStreamChunkType.TOOL_CALL) {
                    if (reply.isFinished()) {
                        return;
                    }
                    if (!sendOrStop(SseEventType.TOOL_CALL,
                            new SseEventData.ToolCall(chunk.toolCall().id(), chunk.toolCall().name()))) {
                        return;
                    }
                    toolCalls.add(chunk.toolCall());
                    reply.processRecorder().startTool(chunk.toolCall().id(), chunk.toolCall().name());
                    return;
                }
                if (chunk.type() == LlmStreamChunkType.DONE && !toolCalls.isEmpty()) {
                    doneSeen.set(true);
                    return;
                }
                if (chunk.type() == LlmStreamChunkType.DONE) {
                    doneSeen.set(true);
                }
                if (isDeltaChunk(chunk)) {
                    sawDelta.set(true);
                }
                if (chunk.type() == LlmStreamChunkType.ERROR
                        && !sawDelta.get()
                        && isContextLengthError(chunk.errorMessage())) {
                    retryRequested.set(true);
                    return;
                }
                handleChunk(chunk);
            });
            return new StreamAttemptOutcome(retryRequested.get(), doneSeen.get(), toolCalls);
        }

        private void handleChunk(LlmStreamChunk chunk) {
            if (reply.isFinished()) {
                return;
            }
            String delta = defaultString(chunk.content());
            switch (chunk.type()) {
                case REASONING_DELTA -> {
                    if (reasoningRequested) {
                        reply.appendReasoning(delta);
                        sendOrStop(SseEventType.REASONING_DELTA, new SseEventData.Delta(delta));
                    }
                }
                case ANSWER_DELTA -> {
                    reply.appendContent(delta);
                    sendOrStop(SseEventType.ANSWER_DELTA, new SseEventData.Delta(delta));
                }
                case ERROR -> {
                    if (reply.tryFinish()) {
                        failAndSendError(chunk.errorMessage(), ERROR_SOURCE_LLM);
                    }
                }
                case DONE -> {
                    if (reply.tryFinish()) {
                        complete();
                    }
                }
                default -> {
                }
            }
        }

        private String executeToolCall(LlmToolCall toolCall) {
            ToolExecutionResult result;
            try {
                skillSynchronizer.syncIfNeeded(request.userId(), request.conversationId(), toolCall.name(),
                        request.skillContext(), skillZipsSynced);
                result = toolRegistry.execute(
                        new ToolExecutionRequest(toolCall.id(), toolCall.name(), toolCall.arguments()),
                        toolContext
                );
            } catch (RuntimeException exception) {
                String message = hasText(exception.getMessage()) ? exception.getMessage() : "工具调用失败";
                reportToolResult(toolCall, ToolExecutionResult.failed(message));
                throw exception;
            }
            reportToolResult(toolCall, result);
            return result.content();
        }

        private void reportToolResult(LlmToolCall toolCall, ToolExecutionResult result) {
            reply.processRecorder().completeTool(toolCall.id(), toolCall.name(), result.status(), result.content());
            sendOrStop(SseEventType.TOOL_RESULT,
                    new SseEventData.ToolResult(toolCall.id(), toolCall.name(), result.status(), result.content()));
        }

        private void retryAfterContextLengthException(
                List<LlmMessage> messages,
                List<LlmToolDefinition> availableTools,
                Exception originalException
        ) {
            try {
                LlmClient client = llmClientFactory.apply(request.runtimeConfig().protocolType());
                StreamAttemptOutcome retryOutcome = streamOnce(client, compactMessagesForRetry(messages), availableTools);
                if (retryOutcome.retryRequested() && reply.tryFinish()) {
                    failAndSendError(CONTEXT_LENGTH_RETRY_FAILED_MESSAGE, ERROR_SOURCE_LLM);
                }
            } catch (LlmStreamCancelledException cancelled) {
                applyCancellation();
            } catch (Exception retryException) {
                if (reply.tryFinish()) {
                    String message = isContextLengthError(retryException.getMessage())
                            ? CONTEXT_LENGTH_RETRY_FAILED_MESSAGE
                            : defaultString(retryException.getMessage());
                    if (message.isBlank()) {
                        message = originalException.getMessage();
                    }
                    failAndSendError(message, ERROR_SOURCE_LLM);
                }
            }
        }

        /**
         * 将助手消息标记为完成；若用户已先一步中止，只回填内容不再推送完成事件。
         */
        private void complete() {
            if (messageStore.markCompleted(reply)) {
                events.send(SseEventType.MESSAGE_COMPLETED,
                        new SseEventData.MessageCompleted(reply.messageId(), reply.message().getStatus()));
            }
        }

        private void failAndSendError(String errorMessage, String source) {
            AssistantMessageStore.FailureResult result = messageStore.markFailed(reply, errorMessage);
            if (!result.updated()) {
                return;
            }
            String code = switch (source) {
                case ERROR_SOURCE_TOOL -> TOOL_LOOP_FAILED;
                case ERROR_SOURCE_SERVER -> RUN_ABORTED;
                default -> LLM_STREAM_FAILED;
            };
            boolean retryable = ERROR_SOURCE_LLM.equals(source) && isContextLengthError(result.message());
            events.send(SseEventType.ERROR, SseEventData.Error.failed(code, result.message(), reply.messageId(),
                    source, retryable));
        }

        private LlmStreamRequest buildRequest(List<LlmMessage> messages, List<LlmToolDefinition> availableTools) {
            AiModelRuntimeConfig runtimeConfig = request.runtimeConfig();
            return new LlmStreamRequest(
                    runtimeConfig.baseUrl(),
                    runtimeConfig.apiKeyCipher(),
                    runtimeConfig.authType(),
                    runtimeConfig.apiVersion(),
                    runtimeConfig.extraHeaders(),
                    runtimeConfig.extraBody(),
                    runtimeConfig.modelCode(),
                    messages,
                    runtimeConfig.maxOutputTokens(),
                    reasoningRequested,
                    availableTools
            );
        }
    }

    private static List<LlmMessage> withSandboxPathRules(List<LlmMessage> messages, List<LlmToolDefinition> tools) {
        List<LlmMessage> resolvedMessages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        if (!hasSandboxTools(tools)) {
            return resolvedMessages;
        }
        String rules = SANDBOX_PATH_RULES.strip();
        for (int index = 0; index < resolvedMessages.size(); index++) {
            LlmMessage message = resolvedMessages.get(index);
            if (!"system".equals(message.role())) {
                continue;
            }
            String content = hasText(message.content()) ? message.content().strip() + "\n\n" + rules : rules;
            resolvedMessages.set(index, new LlmMessage(
                    message.role(),
                    content,
                    message.parts(),
                    message.toolCallId(),
                    message.toolName(),
                    message.toolCalls()
            ));
            return resolvedMessages;
        }
        resolvedMessages.addFirst(new LlmMessage("system", rules));
        return resolvedMessages;
    }

    private static boolean hasSandboxTools(List<LlmToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return false;
        }
        return tools.stream()
                .filter(Objects::nonNull)
                .map(LlmToolDefinition::name)
                .filter(Objects::nonNull)
                .anyMatch(SkillSandboxSynchronizer::isSandboxTool);
    }

    /**
     * 上下文超长时只保留最后一条用户消息重试一次。
     */
    private static List<LlmMessage> compactMessagesForRetry(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            LlmMessage message = messages.get(index);
            if ("user".equals(message.role())) {
                return List.of(message);
            }
        }
        return List.of(messages.getLast());
    }

    private static boolean isDeltaChunk(LlmStreamChunk chunk) {
        return chunk.type() == LlmStreamChunkType.ANSWER_DELTA
                || chunk.type() == LlmStreamChunkType.REASONING_DELTA;
    }

    static boolean isContextLengthError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }
        String normalized = errorMessage.toLowerCase(Locale.ROOT);
        return normalized.contains("context length")
                || normalized.contains("context_length_exceeded")
                || normalized.contains("maximum context")
                || normalized.contains("max context")
                || normalized.contains("too many tokens")
                || normalized.contains("token limit")
                || normalized.contains("超过上下文")
                || normalized.contains("上下文长度")
                || (normalized.contains("token") && normalized.contains("exceed"));
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record StreamAttemptOutcome(boolean retryRequested, boolean doneSeen, List<LlmToolCall> toolCalls) {
    }
}
