package com.agenthub.sandbox.admin.dto;

public record SandboxControlRequest(
        String reason,
        String confirmText,
        boolean dryRun
) {
}
