package com.agenthub.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.service.SandboxSessionService;
import com.agenthub.sandbox.service.SandboxToolService;
import com.agenthub.tool.runtime.ToolEventSink;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class BuiltinSandboxToolProviderTest {

    private final SandboxSessionService sandboxSessionService = mock(SandboxSessionService.class);
    private final SandboxToolService sandboxToolService = mock(SandboxToolService.class);
    private final BuiltinSandboxToolProvider provider = new BuiltinSandboxToolProvider(
            sandboxSessionService,
            sandboxToolService
    );

    @Test
    void shouldExposeSandboxToolsInStableOrder() {
        List<LlmToolDefinition> tools = provider.listTools(context(ToolEventSink.noop()));

        assertThat(tools)
                .extracting(LlmToolDefinition::name)
                .containsExactly(
                        "sandbox.exec",
                        "sandbox.read_file",
                        "sandbox.write_file",
                        "sandbox.list_files",
                        "sandbox.upload_file"
                );
    }

    @Test
    void shouldSupportOnlySandboxTools() {
        assertThat(provider.supports("sandbox.exec")).isTrue();
        assertThat(provider.supports("mcp.x.y")).isFalse();
        assertThat(provider.supports(null)).isFalse();
    }

    @Test
    void shouldEnsureSessionAndReadFile() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");
        when(sandboxToolService.readFile(7L, 100L, "session-1", "/workspace/a.txt", 1024))
                .thenReturn("hello");

        ToolExecutionResult result = provider.execute(
                request("call-1", "sandbox.read_file", Map.of("path", "/workspace/a.txt", "max_bytes", 1024)),
                context(ToolEventSink.noop())
        );

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.content()).isEqualTo("hello");
        verify(sandboxSessionService).ensureSession(7L, 100L);
        verify(sandboxToolService).readFile(7L, 100L, "session-1", "/workspace/a.txt", 1024);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldForwardExecDeltasAndReturnCombinedOutput() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");
        doAnswer(invocation -> {
            Consumer<SandboxEvent> consumer = invocation.getArgument(6);
            consumer.accept(new SandboxEvent("stdout_delta", "exec-1", "out", null, null, null, null, null, false));
            consumer.accept(new SandboxEvent("stderr_delta", "exec-1", "err", null, null, null, null, null, false));
            return null;
        }).when(sandboxToolService).exec(any(), any(), any(), any(), any(), any(Integer.class), any(Consumer.class));
        List<EventOutput> outputs = new ArrayList<>();

        ToolExecutionResult result = provider.execute(
                request("call-1", "sandbox.exec", Map.of("command", "echo ok")),
                context((toolCallId, toolName, stream, content) ->
                        outputs.add(new EventOutput(toolCallId, toolName, stream, content)))
        );

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.content()).isEqualTo("stdout:\nout\nstderr:\nerr");
        assertThat(outputs).containsExactly(
                new EventOutput("call-1", "sandbox.exec", "stdout", "out"),
                new EventOutput("call-1", "sandbox.exec", "stderr", "err")
        );
        verify(sandboxToolService).exec(
                eq(7L),
                eq(100L),
                eq("session-1"),
                eq("echo ok"),
                eq("/workspace"),
                eq(60),
                any(Consumer.class)
        );
    }

    @Test
    void shouldFormatListFilesResult() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");
        when(sandboxToolService.listFiles(7L, 100L, "session-1", "/workspace", false))
                .thenReturn(List.of(
                        new SandboxFileItem("src", "/workspace/src", 0L, true),
                        new SandboxFileItem("out.txt", "/workspace/out.txt", 12L, false)
                ));

        ToolExecutionResult result = provider.execute(
                request("call-1", "sandbox.list_files", Map.of()),
                context(ToolEventSink.noop())
        );

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.content()).isEqualTo("""
                [DIR] /workspace/src 0 bytes
                [FILE] /workspace/out.txt 12 bytes""");
    }

    @Test
    void shouldReturnFailedResultWhenSandboxDependencyThrows() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");
        when(sandboxToolService.readFile(7L, 100L, "session-1", "/workspace/missing.txt", 4096))
                .thenThrow(new IllegalStateException("文件不存在"));

        ToolExecutionResult result = provider.execute(
                request("call-1", "sandbox.read_file", Map.of("path", "/workspace/missing.txt")),
                context(ToolEventSink.noop())
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("文件不存在");
    }

    private ToolExecutionRequest request(String toolCallId, String toolName, Map<String, Object> arguments) {
        return new ToolExecutionRequest(toolCallId, toolName, arguments);
    }

    private ToolExecutionContext context(ToolEventSink eventSink) {
        return new ToolExecutionContext(7L, 100L, 200L, "official-writing", eventSink);
    }

    private record EventOutput(String toolCallId, String toolName, String stream, String content) {
    }
}
