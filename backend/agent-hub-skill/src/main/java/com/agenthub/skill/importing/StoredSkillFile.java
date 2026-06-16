package com.agenthub.skill.importing;

public record StoredSkillFile(
        String relativePath,
        boolean directory,
        long sizeBytes,
        String sha256,
        String bucketName,
        String objectKey,
        String contentType
) {
}
