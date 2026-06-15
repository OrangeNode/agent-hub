package com.agenthub.skill.tool;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.skill.service.SkillRuntimeService;
import com.agenthub.tool.runtime.ToolExecutionContext;
import com.agenthub.tool.runtime.ToolExecutionRequest;
import com.agenthub.tool.runtime.ToolExecutionResult;
import com.agenthub.tool.runtime.ToolProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SkillToolProvider implements ToolProvider {

    private static final String READ_TOOL = "skill.read";
    private static final LlmToolDefinition READ_TOOL_DEFINITION = new LlmToolDefinition(
            READ_TOOL,
            "读取当前会话已启用 Skill 的完整 SKILL.md 说明",
            objectParameters(
                    properties(property("skill_id", "integer")),
                    List.of("skill_id")
            )
    );

    private final SkillRuntimeService skillRuntimeService;

    public SkillToolProvider(SkillRuntimeService skillRuntimeService) {
        this.skillRuntimeService = skillRuntimeService;
    }

    @Override
    public List<LlmToolDefinition> listTools(ToolExecutionContext context) {
        if (context == null || context.skillIds().isEmpty()) {
            return List.of();
        }
        return List.of(READ_TOOL_DEFINITION);
    }

    @Override
    public boolean supports(String toolName) {
        return READ_TOOL.equals(toolName);
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context) {
        if (!supports(request.toolName())) {
            return ToolExecutionResult.failed("不支持的工具: " + request.toolName());
        }
        Long skillId = longArg(request, "skill_id");
        if (skillId == null) {
            return ToolExecutionResult.failed("skill_id 不能为空");
        }
        List<Long> allowedSkillIds = context == null ? List.of() : context.skillIds();
        return ToolExecutionResult.completed(skillRuntimeService.readSkillMarkdown(skillId, allowedSkillIds));
    }

    private static Long longArg(ToolExecutionRequest request, String name) {
        Object value = request.arguments().get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SafeVarargs
    private static Map<String, Object> properties(Map.Entry<String, Map<String, Object>>... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : entries) {
            properties.put(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    private static Map.Entry<String, Map<String, Object>> property(String name, String type) {
        return Map.entry(name, Map.of("type", type));
    }

    private static Map<String, Object> objectParameters(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required
        );
    }
}
