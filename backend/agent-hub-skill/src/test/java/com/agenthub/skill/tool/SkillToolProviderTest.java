package com.agenthub.skill.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.skill.service.SkillRuntimeService;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillToolProviderTest {

    private final SkillRuntimeService runtimeService = mock(SkillRuntimeService.class);
    private final SkillToolProvider provider = new SkillToolProvider(runtimeService);

    @Test
    void shouldExposeReadToolOnlyWhenSkillEnabledInContext() {
        assertThat(provider.listTools(context(List.of()))).isEmpty();

        assertThat(provider.listTools(context(List.of(10L))))
                .extracting(LlmToolDefinition::name)
                .containsExactly("skill.read");
    }

    @Test
    void shouldReadSkillMarkdownWithCurrentContextAllowList() {
        ToolExecutionContext context = context(List.of(10L));
        ToolExecutionRequest request = new ToolExecutionRequest("call_1", "skill.read", Map.of("skill_id", 10));
        when(runtimeService.readSkillMarkdown(10L, List.of(10L))).thenReturn("# 公文写作");

        ToolExecutionResult result = provider.execute(request, context);

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.content()).isEqualTo("# 公文写作");
        verify(runtimeService).readSkillMarkdown(10L, List.of(10L));
    }

    @Test
    void shouldRejectMissingSkillId() {
        ToolExecutionResult result = provider.execute(
                new ToolExecutionRequest("call_1", "skill.read", Map.of()),
                context(List.of(10L))
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.content()).isEqualTo("skill_id 不能为空");
    }

    private ToolExecutionContext context(List<Long> skillIds) {
        return new ToolExecutionContext(7L, 100L, 200L, null, skillIds, null);
    }
}
