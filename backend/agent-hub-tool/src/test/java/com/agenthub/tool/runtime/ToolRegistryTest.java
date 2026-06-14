package com.agenthub.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void shouldAggregateToolsFromAllProviders() {
        ToolProvider first = mock(ToolProvider.class);
        ToolProvider second = mock(ToolProvider.class);
        ToolExecutionContext context = context();
        when(first.listTools(context)).thenReturn(List.of(tool("sandbox.exec")));
        when(second.listTools(context)).thenReturn(List.of(tool("mcp.github.search_repositories")));

        ToolRegistry registry = new ToolRegistry(List.of(first, second));

        assertThat(registry.listTools(context))
                .extracting(LlmToolDefinition::name)
                .containsExactly("sandbox.exec", "mcp.github.search_repositories");
    }

    @Test
    void shouldDispatchExecutionToSupportingProvider() {
        ToolProvider first = mock(ToolProvider.class);
        ToolProvider second = mock(ToolProvider.class);
        ToolExecutionContext context = context();
        ToolExecutionRequest request = new ToolExecutionRequest("call_1", "mcp.github.search_repositories", Map.of());
        when(first.supports("mcp.github.search_repositories")).thenReturn(false);
        when(second.supports("mcp.github.search_repositories")).thenReturn(true);
        when(second.execute(request, context)).thenReturn(ToolExecutionResult.completed("ok"));

        ToolRegistry registry = new ToolRegistry(List.of(first, second));

        assertThat(registry.execute(request, context).content()).isEqualTo("ok");
    }

    @Test
    void shouldReturnFailedResultWhenToolUnsupported() {
        ToolRegistry registry = new ToolRegistry(List.of());

        ToolExecutionResult result = registry.execute(
                new ToolExecutionRequest("call_1", "mcp.missing.tool", Map.of()),
                context()
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("不支持的工具: mcp.missing.tool");
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(7L, 100L, 200L, "official-writing", ToolEventSink.noop());
    }

    private LlmToolDefinition tool(String name) {
        return new LlmToolDefinition(name, "测试工具", Map.of("type", "object"));
    }
}
