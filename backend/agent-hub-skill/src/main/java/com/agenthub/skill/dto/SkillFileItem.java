package com.agenthub.skill.dto;

public record SkillFileItem(
        Long fileId,
        Long skillId,
        String relativePath,
        boolean directory,
        long sizeBytes,
        String sha256
) {
}
