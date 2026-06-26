package com.agenthub.chat.task.runtime;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunkType;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentTaskPlanner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> FALLBACK_STEPS = List.of("分析需求", "准备上下文", "执行任务", "校验结果", "整理回复");

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
        return new LlmStreamRequest(
                runtimeConfig == null ? null : runtimeConfig.baseUrl(),
                runtimeConfig == null ? null : runtimeConfig.apiKeyCipher(),
                runtimeConfig == null ? null : runtimeConfig.apiVersion(),
                runtimeConfig == null ? null : runtimeConfig.modelCode(),
                List.of(
                        new LlmMessage("system", """
                                你是任务规划器。请只输出 JSON，不要输出 Markdown。
                                JSON 结构为 {"title":"任务标题","summary":"计划摘要","steps":["步骤一","步骤二"]}。
                                步骤保持简短、可执行，最多 6 步。
                                """),
                        new LlmMessage("user", prompt)
                ),
                runtimeConfig == null ? 800 : runtimeConfig.maxOutputTokens(),
                false
        );
    }

    private AgentTaskPlan parsePlan(String json, String fallbackTitle) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            String title = textOrDefault(root.path("title"), fallbackTitle);
            String summary = textOrDefault(root.path("summary"), fallbackTitle);
            List<String> steps = new ArrayList<>();
            JsonNode stepNodes = root.path("steps");
            if (stepNodes.isArray()) {
                for (JsonNode stepNode : stepNodes) {
                    String step = stepNode.asText("").trim();
                    if (!step.isEmpty()) {
                        steps.add(step);
                    }
                }
            }
            if (steps.isEmpty()) {
                return fallbackPlan(fallbackTitle);
            }
            return new AgentTaskPlan(title, summary, List.copyOf(steps));
        } catch (Exception exception) {
            return fallbackPlan(fallbackTitle);
        }
    }

    private String textOrDefault(JsonNode node, String defaultValue) {
        String value = node.asText("").trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private AgentTaskPlan fallbackPlan(String prompt) {
        return new AgentTaskPlan(prompt, prompt, FALLBACK_STEPS);
    }

    private String normalizePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        return value.isEmpty() ? "执行任务" : value;
    }
}
