package com.agenthub.skill.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public record SkillUploadUrl(
        Long importId,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        OffsetDateTime expiresAt
) {
}
