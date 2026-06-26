package com.agenthub.chat.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentTaskPlannerTest {

    @Test
    void shouldBuildPlanFromLlmJson() {
        RecordingLlmClient client = new RecordingLlmClient("""
                {
                  "title": "生成调研报告",
                  "summary": "分析资料并输出报告",
                  "steps": ["梳理资料", "生成报告", "校验结果"]
                }
                """);
        AgentTaskPlanner planner = new AgentTaskPlanner(client);

        AgentTaskPlan plan = planner.plan("请分析资料并生成报告");

        assertThat(plan.title()).isEqualTo("生成调研报告");
        assertThat(plan.summary()).isEqualTo("分析资料并输出报告");
        assertThat(plan.steps()).containsExactly("梳理资料", "生成报告", "校验结果");
        assertThat(client.lastRequest.messages()).hasSize(2);
        assertThat(client.lastRequest.messages().getLast().content()).contains("请分析资料并生成报告");
    }

    @Test
    void shouldFallbackToFixedChineseStepsWhenJsonCannotBeParsed() {
        AgentTaskPlanner planner = new AgentTaskPlanner(new RecordingLlmClient("不是 JSON"));

        AgentTaskPlan plan = planner.plan("处理一个复杂任务");

        assertThat(plan.title()).isEqualTo("处理一个复杂任务");
        assertThat(plan.summary()).isEqualTo("处理一个复杂任务");
        assertThat(plan.steps()).containsExactly("分析需求", "准备上下文", "执行任务", "校验结果", "整理回复");
    }

    private static class RecordingLlmClient implements LlmClient {

        private final String content;
        private LlmStreamRequest lastRequest;

        private RecordingLlmClient(String content) {
            this.content = content;
        }

        @Override
        public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
            this.lastRequest = request;
            chunkConsumer.accept(LlmStreamChunk.answer(content));
            chunkConsumer.accept(LlmStreamChunk.done());
        }
    }
}
