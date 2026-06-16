package com.agenthub.sandbox.client.dto;

import java.util.List;

public record SkillZipSyncRequest(List<Skill> skills) {

    public SkillZipSyncRequest {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public record Skill(
            Long skillId,
            String skillKey,
            String name,
            String zipDownloadUrl,
            String zipSha256,
            String targetDir
    ) {
    }
}
