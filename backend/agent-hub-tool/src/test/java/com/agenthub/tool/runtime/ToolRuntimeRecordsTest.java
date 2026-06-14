package com.agenthub.tool.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRuntimeRecordsTest {

    @Test
    void shouldUseEmptyMapWhenArgumentsIsNull() {
        ToolExecutionRequest request = new ToolExecutionRequest("call_1", "sandbox.exec", null);

        assertThat(request.arguments()).isEmpty();
    }

    @Test
    void shouldAllowNullArgumentValue() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("command", null);

        ToolExecutionRequest request = new ToolExecutionRequest("call_1", "sandbox.exec", arguments);

        assertThat(request.arguments()).containsEntry("command", null);
    }

    @Test
    void shouldCopyArgumentsWithoutExternalMutation() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("command", "pwd");

        ToolExecutionRequest request = new ToolExecutionRequest("call_1", "sandbox.exec", arguments);
        arguments.put("command", "ls");

        assertThat(request.arguments()).containsEntry("command", "pwd");
    }

    @Test
    void shouldUseNoopEventSinkWhenEventSinkIsNull() {
        ToolExecutionContext context = new ToolExecutionContext(7L, 100L, 200L, "official-writing", null);

        assertThatCode(() -> context.eventSink().output("call_1", "sandbox.exec", "stdout", "ok"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNormalizeNullResultContent() {
        assertThat(ToolExecutionResult.completed(null).content()).isEmpty();
        assertThat(ToolExecutionResult.failed(null).content()).isEmpty();
    }
}
