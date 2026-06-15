package com.agenthub.skill.importing;

public record ParsedSkillFile(
        String relativePath,
        boolean directory,
        long sizeBytes,
        String sha256
) {
}
