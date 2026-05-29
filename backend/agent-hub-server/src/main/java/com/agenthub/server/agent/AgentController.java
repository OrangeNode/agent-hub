package com.agenthub.server.agent;

import com.agenthub.agent.definition.AgentDefinitionItem;
import com.agenthub.common.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    @GetMapping
    public ApiResponse<List<AgentDefinitionItem>> listAgents() {
        return ApiResponse.success(List.of(
                new AgentDefinitionItem("ops-agent", "运维 Agent", "用于基础运维巡检与响应"),
                new AgentDefinitionItem("support-agent", "支持 Agent", "用于知识问答和流程辅助")
        ));
    }
}
