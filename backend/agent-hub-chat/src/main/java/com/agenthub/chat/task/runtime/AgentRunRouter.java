package com.agenthub.chat.task.runtime;

import org.springframework.stereotype.Component;

@Component
public class AgentRunRouter {

    public AgentRunDecision route(AgentRunInput input) {
        String prompt = input == null || input.prompt() == null ? "" : input.prompt().trim().toLowerCase();
        if (isFileDeliverableTask(prompt)) {
            return new AgentRunDecision(RunMode.PLAN_EXECUTE, ToolPolicy.AUTO, PlanPolicy.AUTO);
        }
        if (isComplexTask(prompt)) {
            return new AgentRunDecision(RunMode.PLAN_EXECUTE, ToolPolicy.AUTO, PlanPolicy.AUTO);
        }
        if (isLightToolQuery(prompt)) {
            return new AgentRunDecision(RunMode.TOOL_ASSISTED, ToolPolicy.AUTO, PlanPolicy.NONE);
        }
        return new AgentRunDecision(RunMode.DIRECT_CHAT, ToolPolicy.NONE, PlanPolicy.NONE);
    }

    private boolean isComplexTask(String prompt) {
        if (isMultiStageProjectPlan(prompt)) {
            return true;
        }
        return containsAny(prompt, "生成报告", "制作", "ppt", "可下载", "分析", "整理成", "转换", "导出")
                && containsAny(prompt, "并", "然后", "同时", "多步", "完整", "计划", "报告", "ppt");
    }

    private boolean isMultiStageProjectPlan(String prompt) {
        return containsAny(prompt, "计划", "方案", "规划", "路线图", "实施", "上线")
                && containsAny(prompt, "包含", "里程碑", "风险", "交付", "阶段", "三个月", "完整", "多步", "拆解");
    }

    private boolean isFileDeliverableTask(String prompt) {
        return containsAny(prompt, "下载", "可下载", "导出")
                && containsAny(prompt, "代码", "文件", "input", "output", "csv", "xlsx", "docx", "ppt", "pdf");
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
