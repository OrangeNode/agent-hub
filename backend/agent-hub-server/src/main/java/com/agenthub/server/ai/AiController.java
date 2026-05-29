package com.agenthub.server.ai;

import com.agenthub.agent.mcp.McpServerItem;
import com.agenthub.agent.skill.SkillItem;
import com.agenthub.ai.model.AiModelItem;
import com.agenthub.ai.provider.AiProviderItem;
import com.agenthub.common.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiController {

    @GetMapping("/api/ai/providers")
    public ApiResponse<List<AiProviderItem>> listProviders() {
        return ApiResponse.success(List.of(
                new AiProviderItem("openai", "OpenAI", "https://api.openai.com", "启用"),
                new AiProviderItem("azure-openai", "Azure OpenAI", "https://example.openai.azure.com", "草稿")
        ));
    }

    @GetMapping("/api/ai/models")
    public ApiResponse<List<AiModelItem>> listModels() {
        return ApiResponse.success(List.of(
                new AiModelItem("openai", "gpt-4.1", "GPT-4.1", "启用"),
                new AiModelItem("openai", "gpt-4.1-mini", "GPT-4.1 Mini", "启用")
        ));
    }

    @GetMapping("/api/mcp/servers")
    public ApiResponse<List<McpServerItem>> listMcpServers() {
        return ApiResponse.success(List.of(
                new McpServerItem("mcp-local", "本地 MCP 服务", "启用")
        ));
    }

    @GetMapping("/api/skills")
    public ApiResponse<List<SkillItem>> listSkills() {
        return ApiResponse.success(List.of(
                new SkillItem("planner", "Planner", "0.1.0"),
                new SkillItem("tool-runner", "Tool Runner", "0.1.0"),
                new SkillItem("retriever", "Retriever", "0.1.0")
        ));
    }
}
