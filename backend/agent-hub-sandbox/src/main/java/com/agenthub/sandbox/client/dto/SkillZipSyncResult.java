package com.agenthub.sandbox.client.dto;

import java.util.List;

public record SkillZipSyncResult(boolean ok, List<SkillResult> skills) {

    public SkillZipSyncResult {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public record SkillResult(
            Long skillId,
            String skillKey,
            String targetDir,
            String archivePath,
            Integer fileCount,
            Long totalBytes
    ) {
    }
}
