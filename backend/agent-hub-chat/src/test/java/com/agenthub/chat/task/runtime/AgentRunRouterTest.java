package com.agenthub.chat.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentRunRouterTest {

    private final AgentRunRouter router = new AgentRunRouter();

    @Test
    void shouldRouteCasualChatToDirectChatWithoutToolsOrPlan() {
        AgentRunDecision decision = router.route(new AgentRunInput("你好，今天心情怎么样？"));

        assertThat(decision.runMode()).isEqualTo(RunMode.DIRECT_CHAT);
        assertThat(decision.toolPolicy()).isEqualTo(ToolPolicy.NONE);
        assertThat(decision.planPolicy()).isEqualTo(PlanPolicy.NONE);
    }

    @Test
    void shouldRouteObviouslyComplexTaskToPlanExecute() {
        AgentRunDecision decision = router.route(new AgentRunInput("请分析这份材料，生成报告并制作可下载的 PPT"));

        assertThat(decision.runMode()).isEqualTo(RunMode.PLAN_EXECUTE);
        assertThat(decision.toolPolicy()).isEqualTo(ToolPolicy.AUTO);
        assertThat(decision.planPolicy()).isEqualTo(PlanPolicy.AUTO);
    }

    @Test
    void shouldRouteLightToolQueryToToolAssistedWithoutPlan() {
        AgentRunDecision decision = router.route(new AgentRunInput("帮我查一下今天北京天气"));

        assertThat(decision.runMode()).isEqualTo(RunMode.TOOL_ASSISTED);
        assertThat(decision.toolPolicy()).isEqualTo(ToolPolicy.AUTO);
        assertThat(decision.planPolicy()).isEqualTo(PlanPolicy.NONE);
    }
}
