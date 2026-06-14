package com.agenthub.tool.runtime;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import java.util.List;

public interface ToolProvider {

    List<LlmToolDefinition> listTools(ToolExecutionContext context);

    boolean supports(String toolName);

    ToolExecutionResult execute(ToolExecutionRequest request, ToolExecutionContext context);
}
