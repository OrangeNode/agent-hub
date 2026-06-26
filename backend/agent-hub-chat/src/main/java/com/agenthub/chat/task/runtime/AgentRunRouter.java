package com.agenthub.chat.task.runtime;

import org.springframework.stereotype.Component;

@Component
public class AgentRunRouter {

    public AgentRunDecision route(AgentRunInput input) {
        String prompt = input == null || input.prompt() == null ? "" : input.prompt().trim().toLowerCase();
        if (isComplexTask(prompt)) {
            return new AgentRunDecision(RunMode.PLAN_EXECUTE, ToolPolicy.AUTO, PlanPolicy.AUTO);
        }
        if (isLightToolQuery(prompt)) {
            return new AgentRunDecision(RunMode.TOOL_ASSISTED, ToolPolicy.AUTO, PlanPolicy.NONE);
        }
        return new AgentRunDecision(RunMode.DIRECT_CHAT, ToolPolicy.NONE, PlanPolicy.NONE);
    }

    private boolean isComplexTask(String prompt) {
        return containsAny(prompt, "生成报告", "制作", "ppt", "可下载", "分析", "整理成", "转换", "导出")
                && containsAny(prompt, "并", "然后", "同时", "多步", "完整", "计划", "报告", "ppt");
    }

    private boolean isLightToolQuery(String prompt) {
        return containsAny(prompt, "查一下", "查询", "搜索", "最新")
                || containsAny(prompt, "天气", "价格", "汇率");
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
