package com.agenthub.skill.dto;

public record SkillFileContent(
        Long fileId,
        Long skillId,
        String relativePath,
        long sizeBytes,
        boolean previewable,
        String content,
        String message
) {
}
