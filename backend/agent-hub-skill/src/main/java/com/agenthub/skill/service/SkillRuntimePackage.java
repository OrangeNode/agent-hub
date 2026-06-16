package com.agenthub.skill.service;

public record SkillRuntimePackage(
        Long skillId,
        String skillKey,
        String name,
        String bucketName,
        String objectKey,
        String zipSha256
) {
}
