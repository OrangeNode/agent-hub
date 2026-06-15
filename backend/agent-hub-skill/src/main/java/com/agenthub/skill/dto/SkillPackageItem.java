package com.agenthub.skill.dto;

import java.time.OffsetDateTime;

public record SkillPackageItem(
        Long skillId,
        String skillKey,
        String name,
        String description,
        String originalFilename,
        String skillMdContent,
        Integer fileCount,
        OffsetDateTime updatedAt
) {
}
