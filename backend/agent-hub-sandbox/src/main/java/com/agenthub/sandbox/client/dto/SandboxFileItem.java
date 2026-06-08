package com.agenthub.sandbox.client.dto;

public record SandboxFileItem(
        String name,
        String path,
        Long size,
        boolean directory
) {
}
