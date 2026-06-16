package com.agenthub.skill.importing;

import java.util.List;

public record ParsedSkillArchive(
        String name,
        String description,
        String rootDir,
        String skillMdPath,
        String skillMdContent,
        String zipSha256,
        List<ParsedSkillFile> files
) {
    public ParsedSkillArchive {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
