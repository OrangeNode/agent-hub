package com.agenthub.agent.tool;

import java.util.List;

public class SimpleToolRegistry implements ToolRegistry {

    @Override
    public List<String> listTools() {
        return List.of("search", "memory", "calculator");
    }
}
