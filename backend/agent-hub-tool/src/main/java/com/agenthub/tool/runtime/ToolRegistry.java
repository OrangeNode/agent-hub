package com.agenthub.tool.runtime;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ToolRegistry {

    private final List<ToolProvider> providers;

    public ToolRegistry(List<ToolProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public List<LlmToolDefinition> listTools(ToolExecutionContext context) {
        return providers.stream()
                .flatMap(provider -> provider.listTools(context).stream())
                .toList();
    }

    public ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context) {
        return providers.stream()
                .filter(provider -> provider.supports(request.toolName()))
                .findFirst()
                .map(provider -> provider.execute(request, context))
                .orElseGet(() -> ToolExecutionResult.failed("不支持的工具: " + request.toolName()));
    }
}
