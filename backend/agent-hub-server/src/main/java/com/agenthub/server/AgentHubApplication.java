package com.agenthub.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.agenthub")
@MapperScan({
        "com.agenthub.system.user.mapper",
        "com.agenthub.system.role.mapper",
        "com.agenthub.system.menu.mapper"
})
public class AgentHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentHubApplication.class, args);
    }
}
