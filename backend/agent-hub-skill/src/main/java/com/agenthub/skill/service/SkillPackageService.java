package com.agenthub.skill.service;

import com.agenthub.skill.command.CreateSkillUploadCommand;
import com.agenthub.skill.command.SkillImportApplyCommand;
import com.agenthub.skill.dto.SkillFileContent;
import com.agenthub.skill.dto.SkillFileItem;
import com.agenthub.skill.dto.SkillImportPreview;
import com.agenthub.skill.dto.SkillOptionItem;
import com.agenthub.skill.dto.SkillPackageItem;
import com.agenthub.skill.dto.SkillUploadUrl;
import java.util.List;

public interface SkillPackageService {

    List<SkillPackageItem> list();

    List<SkillOptionItem> listAvailable();

    SkillUploadUrl createUploadUrl(Long userId, CreateSkillUploadCommand command);

    SkillImportPreview confirmImport(Long userId, Long importId);

    SkillPackageItem applyImport(Long userId, Long importId, SkillImportApplyCommand command);

    List<SkillFileItem> files(Long skillId);

    SkillFileContent fileContent(Long skillId, String relativePath);

    void delete(Long skillId);
}
