package com.agenthub.skill.importing;

import java.util.List;

public record ParsedSkillArchive(
        String name,
        String description,
        String rootDir,
        String skillMdPath,
        String skillMdContent,
        List<ParsedSkillFile> files
) {
}
