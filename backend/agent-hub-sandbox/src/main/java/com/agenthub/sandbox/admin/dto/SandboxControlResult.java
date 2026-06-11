package com.agenthub.sandbox.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SandboxControlResult(
        boolean success,
        List<String> affected,
        List<String> errors
) {
    public SandboxControlResult {
        affected = affected == null ? List.of() : List.copyOf(affected);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
