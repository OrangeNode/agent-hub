package com.agenthub.skill.dto;

public record SkillImportPreview(
        Long importId,
        boolean duplicate,
        boolean imported,
        Long duplicateSkillId,
        String parsedName,
        String parsedDescription,
        int fileCount,
        String skillMdContent,
        SkillPackageItem importedSkill
) {
}
