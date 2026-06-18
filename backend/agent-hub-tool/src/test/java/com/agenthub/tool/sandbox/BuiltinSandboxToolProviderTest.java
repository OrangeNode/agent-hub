package com.agenthub.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
        assertThat(provider.supports("sandbox.unknown")).isFalse();
        assertThat(provider.supports(null)).isFalse();
    }

    @Test
    void shouldReturnFailedResultWhenExecutingUnsupportedTool() {
        ToolExecutionResult result = provider.execute(
                request("call-1", "sandbox.unknown", Map.of()),
                context(ToolEventSink.noop())
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("不支持的工具: sandbox.unknown");
        verifyNoInteractions(sandboxSessionService, sandboxToolService);
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
    void shouldParseStringNumberAndFallbackDefaultForReadFileMaxBytes() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");

        provider.execute(
                request("call-1", "sandbox.read_file", Map.of("path", "/workspace/a.txt", "max_bytes", "2048")),
                context(ToolEventSink.noop())
        );
        provider.execute(
                request("call-2", "sandbox.read_file", Map.of("path", "/workspace/b.txt", "max_bytes", "bad")),
                context(ToolEventSink.noop())
        );

        verify(sandboxToolService).readFile(7L, 100L, "session-1", "/workspace/a.txt", 2048);
        verify(sandboxToolService).readFile(7L, 100L, "session-1", "/workspace/b.txt", 4096);
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
                eq(300),
                any(Consumer.class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldParseStringNumberAndFallbackDefaultForExecTimeout() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");

        provider.execute(
                request("call-1", "sandbox.exec", Map.of("command", "pwd", "timeout_seconds", "12")),
                context(ToolEventSink.noop())
        );
        provider.execute(
                request("call-2", "sandbox.exec", Map.of("command", "ls", "timeout_seconds", "bad")),
                context(ToolEventSink.noop())
        );

        verify(sandboxToolService).exec(eq(7L), eq(100L), eq("session-1"), eq("pwd"), eq("/workspace"),
                eq(12), any(Consumer.class));
        verify(sandboxToolService).exec(eq(7L), eq(100L), eq("session-1"), eq("ls"), eq("/workspace"),
                eq(300), any(Consumer.class));
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
                dir /workspace/src
                file /workspace/out.txt""");
    }

    @Test
    void shouldParseStringBooleanForListFilesRecursive() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");

        provider.execute(
                request("call-1", "sandbox.list_files", Map.of("recursive", "true")),
                context(ToolEventSink.noop())
        );
        provider.execute(
                request("call-2", "sandbox.list_files", Map.of("recursive", "bad")),
                context(ToolEventSink.noop())
        );

        verify(sandboxToolService).listFiles(7L, 100L, "session-1", "/workspace", true);
        verify(sandboxToolService).listFiles(7L, 100L, "session-1", "/workspace", false);
    }

    @Test
    void shouldSendWriteFilePreviewAndParseStringBooleanAppend() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");
        List<EventOutput> outputs = new ArrayList<>();
        String content = "a".repeat(4000) + "Z";

        ToolExecutionResult result = provider.execute(
                request("call-1", "sandbox.write_file", Map.of(
                        "path", "/workspace/artifacts/a.txt",
                        "content", content,
                        "append", "true"
                )),
                context((toolCallId, toolName, stream, output) ->
                        outputs.add(new EventOutput(toolCallId, toolName, stream, output)))
        );

        assertThat(result.status()).isEqualTo("completed");
        assertThat(outputs).containsExactly(new EventOutput(
                "call-1",
                "sandbox.write_file",
                "stdout",
                "写入文件: /workspace/artifacts/a.txt\n" + "a".repeat(4000) + "\n...（内容已截断）"
        ));
        verify(sandboxToolService).writeFile(7L, 100L, "session-1",
                "/workspace/artifacts/a.txt", content, true);
    }

    @Test
    void shouldFallbackDefaultForInvalidStringAppend() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");

        provider.execute(
                request("call-1", "sandbox.write_file", Map.of(
                        "path", "/workspace/artifacts/a.txt",
                        "content", "hello",
                        "append", "bad"
                )),
                context(ToolEventSink.noop())
        );

        verify(sandboxToolService).writeFile(7L, 100L, "session-1",
                "/workspace/artifacts/a.txt", "hello", false);
    }

    @Test
    void shouldParseStringNumberAndFallbackNullForUploadAttachmentId() {
        when(sandboxSessionService.ensureSession(7L, 100L)).thenReturn("session-1");

        provider.execute(
                request("call-1", "sandbox.upload_file", Map.of(
                        "attachment_id", "99",
                        "target_path", "/workspace/input/a.bin"
                )),
                context(ToolEventSink.noop())
        );
        provider.execute(
                request("call-2", "sandbox.upload_file", Map.of(
                        "attachment_id", "bad",
                        "target_path", "/workspace/input/b.bin"
                )),
                context(ToolEventSink.noop())
        );

        verify(sandboxToolService).uploadFile(7L, 100L, "session-1", 99L, "/workspace/input/a.bin");
        verify(sandboxToolService).uploadFile(eq(7L), eq(100L), eq("session-1"), isNull(),
                eq("/workspace/input/b.bin"));
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
