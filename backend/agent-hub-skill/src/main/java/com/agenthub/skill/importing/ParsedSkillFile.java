package com.agenthub.skill.importing;

public record ParsedSkillFile(
        String relativePath,
        boolean directory,
        long sizeBytes,
        String sha256,
        String contentType,
        byte[] bytes
) {
    public ParsedSkillFile {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
