package com.agenthub.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.agenthub")
@EnableScheduling
@MapperScan({
        "com.agenthub.system.user.mapper",
        "com.agenthub.system.role.mapper",
        "com.agenthub.system.menu.mapper",
        "com.agenthub.system.operationlog.mapper",
        "com.agenthub.ai.provider.mapper",
        "com.agenthub.ai.model.mapper",
        "com.agenthub.agent.mapper",
        "com.agenthub.skill.mapper",
        "com.agenthub.sandbox.session.mapper",
        "com.agenthub.sandbox.execution.mapper",
        "com.agenthub.sandbox.artifact.mapper",
        "com.agenthub.tool.mcp.mapper",
        "com.agenthub.tool.binding.mapper",
        "com.agenthub.chat.conversation.mapper",
        "com.agenthub.chat.message.mapper",
        "com.agenthub.chat.task.mapper",
        "com.agenthub.storage.attachment.mapper",
        "com.agenthub.chat.summary.mapper"
})
public class AgentHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentHubApplication.class, args);
    }
}
