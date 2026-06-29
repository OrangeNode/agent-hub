package com.agenthub.chat.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

class AgentTaskPlannerTest {

    @Test
    void shouldBeRegisteredAsSpringService() {
        assertThat(AgentTaskPlanner.class).hasAnnotation(Service.class);
    }

    @Test
    void shouldBuildPlanFromLlmJson() {
        RecordingLlmClient client = new RecordingLlmClient("""
                {
                  "title": "生成调研报告",
                  "summary": "分析资料并输出报告",
                  "steps": ["梳理资料", "生成报告", "校验结果"]
                }
                """);
        AgentTaskPlanner planner = new AgentTaskPlanner();

        AgentTaskPlan plan = planner.plan(client, runtimeConfig(), "请分析资料并生成报告");

        assertThat(plan.title()).isEqualTo("生成调研报告");
        assertThat(plan.summary()).isEqualTo("分析资料并输出报告");
        assertThat(plan.steps()).containsExactly("梳理资料", "生成报告", "校验结果");
        assertThat(client.lastRequest.messages()).hasSize(2);
        assertThat(client.lastRequest.messages().getLast().content()).contains("请分析资料并生成报告");
        assertThat(client.lastRequest.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(client.lastRequest.apiVersion()).isEqualTo("v1");
        assertThat(client.lastRequest.model()).isEqualTo("gpt-test");
        assertThat(client.lastRequest.maxOutputTokens()).isEqualTo(800);
    }

    @Test
    void shouldParseFencedJsonWithSurroundingText() {
        RecordingLlmClient client = new RecordingLlmClient("""
                下面是计划：
                ```json
                {
                  "title": "生成调研报告",
                  "summary": "分析资料并输出报告",
                  "steps": ["梳理资料", "生成报告", "校验结果"]
                }
                ```
                已完成。
                """);
        AgentTaskPlanner planner = new AgentTaskPlanner();

        AgentTaskPlan plan = planner.plan(client, runtimeConfig(), "请分析资料并生成报告");

        assertThat(plan.title()).isEqualTo("生成调研报告");
        assertThat(plan.steps()).containsExactly("梳理资料", "生成报告", "校验结果");
    }

    @Test
    void shouldTrimLongFieldsDeduplicateAndKeepAtMostSixSteps() {
        String longTitle = "标".repeat(140);
        String longSummary = "摘".repeat(600);
        String longStep = "步".repeat(200);
        RecordingLlmClient client = new RecordingLlmClient("""
                {
                  "title": "%s",
                  "summary": "%s",
                  "steps": ["%s", "%s", "第二步", "第三步", "第四步", "第五步", "第六步", "第七步"]
                }
                """.formatted(longTitle, longSummary, longStep, longStep));
        AgentTaskPlanner planner = new AgentTaskPlanner();

        AgentTaskPlan plan = planner.plan(client, runtimeConfig(), "请生成计划");

        assertThat(plan.title()).hasSize(128);
        assertThat(plan.summary()).hasSize(512);
        assertThat(plan.steps()).hasSize(6);
        assertThat(plan.steps().getFirst()).hasSize(160);
        assertThat(plan.steps()).doesNotHaveDuplicates();
        assertThat(plan.steps()).doesNotContain("第七步");
    }

    @Test
    void shouldFallbackToPromptAwareStepsWhenJsonCannotBeParsed() {
        RecordingLlmClient client = new RecordingLlmClient("不是 JSON");
        AgentTaskPlanner planner = new AgentTaskPlanner();

        AgentTaskPlan plan = planner.plan(client, runtimeConfig(), "请生成报告，并分析数据，然后整理成完整计划。");

        assertThat(plan.title()).isEqualTo("请生成报告，并分析数据，然后整理成完整计划。");
        assertThat(plan.summary()).isEqualTo("请生成报告，并分析数据，然后整理成完整计划。");
        assertThat(plan.steps()).containsExactly(
                "明确报告目标和数据范围",
                "分析数据并提炼结论",
                "生成报告内容",
                "整理完整计划",
                "校验并完善输出"
        );
        assertThat(plan.steps()).doesNotContain("执行任务");
    }

    @Test
    void shouldPreferFeatureImplementationFallbackOverReportKeywords() {
        RecordingLlmClient client = new RecordingLlmClient("不是 JSON");
        AgentTaskPlanner planner = new AgentTaskPlanner();

        AgentTaskPlan plan = planner.plan(client, runtimeConfig(),
                "请设计用户画像分析功能，包含需求拆解、后端接口、前端页面、数据模型、测试计划和交付步骤。");

        assertThat(plan.steps()).containsExactly(
                "梳理功能目标和业务边界",
                "设计数据模型和接口契约",
                "规划前端交互和权限校验",
                "拆解实现步骤和异常处理",
                "制定测试与交付检查"
        );
        assertThat(plan.steps()).doesNotContain("明确报告目标和数据范围", "执行任务");
    }

    @Test
    void shouldUseProjectPlanningFallbackForPlanWithoutReportKeywords() {
        RecordingLlmClient client = new RecordingLlmClient("不是 JSON");
        AgentTaskPlanner planner = new AgentTaskPlanner();

        AgentTaskPlan plan = planner.plan(client, runtimeConfig(),
                "请制定三个月产品上线计划，包含里程碑、风险和交付检查。");

        assertThat(plan.steps()).containsExactly(
                "明确目标范围和约束条件",
                "拆解关键阶段和执行事项",
                "安排里程碑和责任分工",
                "识别风险并制定应对措施",
                "校验交付结果并整理复盘"
        );
        assertThat(plan.steps()).doesNotContain("明确报告目标和数据范围", "执行任务");
    }

    @Test
    void shouldFallbackWhenPlanHasLessThanThreeSteps() {
        RecordingLlmClient client = new RecordingLlmClient("""
                {"title": "短计划", "summary": "步骤太少", "steps": ["一步", "二步"]}
                """);
        AgentTaskPlanner planner = new AgentTaskPlanner();

        AgentTaskPlan plan = planner.plan(client, runtimeConfig(), "处理一个复杂任务");

        assertThat(plan.steps()).containsExactly("理解任务目标", "处理任务内容", "整理回复结果");
    }

    private AiModelRuntimeConfig runtimeConfig() {
        return new AiModelRuntimeConfig(
                10L,
                20L,
                "openai",
                "OpenAI",
                "LLM",
                "OPENAI_COMPATIBLE",
                "https://api.example.com",
                "sk-cipher",
                "BEARER",
                "v1",
                "{}",
                "{}",
                "gpt-test",
                "测试模型",
                true,
                true,
                true,
                null,
                8_000,
                800,
                true,
                true
        );
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
