package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxOrphanResource(
        String type,
        String id,
        String path,
        String reason
) {
}
