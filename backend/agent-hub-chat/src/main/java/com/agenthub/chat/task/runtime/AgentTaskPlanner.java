package com.agenthub.chat.task.runtime;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunkType;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AgentTaskPlanner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> GENERIC_FALLBACK_STEPS = List.of("理解任务目标", "处理任务内容", "整理回复结果");
    private static final List<String> FEATURE_IMPLEMENTATION_STEPS = List.of(
            "梳理功能目标和业务边界",
            "设计数据模型和接口契约",
            "规划前端交互和权限校验",
            "拆解实现步骤和异常处理",
            "制定测试与交付检查"
    );
    private static final List<String> REPORT_ANALYSIS_PLAN_STEPS = List.of(
            "明确报告目标和数据范围",
            "分析数据并提炼结论",
            "生成报告内容",
            "整理完整计划",
            "校验并完善输出"
    );
    private static final List<String> PROJECT_PLANNING_STEPS = List.of(
            "明确目标范围和约束条件",
            "拆解关键阶段和执行事项",
            "安排里程碑和责任分工",
            "识别风险并制定应对措施",
            "校验交付结果并整理复盘"
    );
    private static final int MAX_TITLE_LENGTH = 128;
    private static final int MAX_SUMMARY_LENGTH = 512;
    private static final int MAX_STEP_LENGTH = 160;
    private static final int MAX_STEP_COUNT = 6;
    private static final int MIN_STEP_COUNT = 3;

    public AgentTaskPlan plan(LlmClient client, AiModelRuntimeConfig runtimeConfig, String prompt) {
        String normalizedPrompt = normalizePrompt(prompt);
        StringBuilder content = new StringBuilder();
        try {
            client.stream(request(runtimeConfig, normalizedPrompt), chunk -> {
                if (chunk.type() == LlmStreamChunkType.ANSWER_DELTA && chunk.content() != null) {
                    content.append(chunk.content());
                }
            });
            return parsePlan(content.toString(), normalizedPrompt);
        } catch (RuntimeException exception) {
            return fallbackPlan(normalizedPrompt);
        }
    }

    private LlmStreamRequest request(AiModelRuntimeConfig runtimeConfig, String prompt) {
        Integer maxOutputTokens = runtimeConfig == null ? 800 : runtimeConfig.maxOutputTokens();
        return new LlmStreamRequest(
                runtimeConfig == null ? null : runtimeConfig.baseUrl(),
                runtimeConfig == null ? null : runtimeConfig.apiKeyCipher(),
                runtimeConfig == null ? null : runtimeConfig.apiVersion(),
                runtimeConfig == null ? null : runtimeConfig.modelCode(),
                List.of(
                        new LlmMessage("system", """
                                你是任务规划器。请只输出 JSON，不要输出 Markdown。
                                JSON 结构为 {"title":"任务标题","summary":"计划摘要","steps":["步骤一","步骤二"]}。
                                步骤必须贴合用户的具体需求，不要使用固定模板。
                                步骤保持简短、可执行，最多 6 步。
                                """),
                        new LlmMessage("user", prompt)
                ),
                maxOutputTokens == null ? 800 : maxOutputTokens,
                false
        );
    }

    private AgentTaskPlan parsePlan(String rawContent, String fallbackTitle) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(extractJsonObject(rawContent));
            String title = textOrDefault(root.path("title"), fallbackTitle);
            String summary = textOrDefault(root.path("summary"), fallbackTitle);
            List<String> steps = new ArrayList<>();
            JsonNode stepNodes = root.path("steps");
            if (stepNodes.isArray()) {
                for (JsonNode stepNode : stepNodes) {
                    steps.add(stepNode.asText(""));
                }
            }
            return normalizePlan(title, summary, steps, fallbackTitle);
        } catch (Exception exception) {
            return fallbackPlan(fallbackTitle);
        }
    }

    private String extractJsonObject(String content) {
        if (content == null) {
            throw new IllegalArgumentException("任务计划为空");
        }
        int start = content.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("任务计划不是 JSON");
        }
        boolean inString = false;
        boolean escaping = false;
        int depth = 0;
        for (int i = start; i < content.length(); i++) {
            char current = content.charAt(i);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaping = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("任务计划 JSON 不完整");
    }

    private AgentTaskPlan normalizePlan(String title, String summary, List<String> steps, String fallbackTitle) {
        List<String> normalizedSteps = normalizeSteps(steps);
        if (normalizedSteps.size() < MIN_STEP_COUNT) {
            return fallbackPlan(fallbackTitle);
        }
        String normalizedTitle = limit(defaultText(title, fallbackTitle), MAX_TITLE_LENGTH);
        String normalizedSummary = limit(defaultText(summary, fallbackTitle), MAX_SUMMARY_LENGTH);
        return new AgentTaskPlan(normalizedTitle, normalizedSummary, normalizedSteps);
    }

    private List<String> normalizeSteps(List<String> steps) {
        Set<String> uniqueSteps = new LinkedHashSet<>();
        if (steps != null) {
            for (String step : steps) {
                String value = step == null ? "" : step.trim();
                if (!value.isEmpty()) {
                    uniqueSteps.add(limit(value, MAX_STEP_LENGTH));
                }
                if (uniqueSteps.size() >= MAX_STEP_COUNT) {
                    break;
                }
            }
        }
        return List.copyOf(uniqueSteps);
    }

    private String textOrDefault(JsonNode node, String defaultValue) {
        String value = node.asText("").trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private AgentTaskPlan fallbackPlan(String prompt) {
        String title = limit(defaultText(prompt, "处理任务"), MAX_TITLE_LENGTH);
        String summary = limit(defaultText(prompt, "处理任务"), MAX_SUMMARY_LENGTH);
        return new AgentTaskPlan(title, summary, fallbackSteps(prompt));
    }

    private String normalizePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        return value.isEmpty() ? "处理任务" : value;
    }

    private String defaultText(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return "处理任务";
    }

    private List<String> fallbackSteps(String prompt) {
        String normalizedPrompt = prompt == null ? "" : prompt;
        if (isFeatureImplementationPrompt(normalizedPrompt)) {
            return FEATURE_IMPLEMENTATION_STEPS;
        }
        if (containsAny(normalizedPrompt, "报告", "分析", "数据")) {
            return REPORT_ANALYSIS_PLAN_STEPS;
        }
        if (containsAny(normalizedPrompt, "计划", "方案", "规划", "设计")) {
            return PROJECT_PLANNING_STEPS;
        }
        return GENERIC_FALLBACK_STEPS;
    }

    private boolean isFeatureImplementationPrompt(String value) {
        return containsAny(value,
                "功能",
                "接口",
                "前端",
                "后端",
                "实现",
                "开发",
                "权限",
                "异步",
                "测试",
                "数据模型",
                "页面",
                "下载"
        );
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
