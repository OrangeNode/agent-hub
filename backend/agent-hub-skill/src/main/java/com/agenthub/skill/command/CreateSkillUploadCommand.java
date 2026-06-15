package com.agenthub.skill.command;

public record CreateSkillUploadCommand(
        String filename,
        String contentType,
        long fileSize
) {
}
