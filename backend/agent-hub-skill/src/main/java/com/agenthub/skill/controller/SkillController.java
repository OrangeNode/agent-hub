package com.agenthub.skill.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.skill.command.CreateSkillUploadCommand;
import com.agenthub.skill.command.SkillImportApplyCommand;
import com.agenthub.skill.controller.request.ApplySkillImportRequest;
import com.agenthub.skill.controller.request.CreateSkillUploadRequest;
import com.agenthub.skill.dto.SkillFileContent;
import com.agenthub.skill.dto.SkillFileItem;
import com.agenthub.skill.dto.SkillImportPreview;
import com.agenthub.skill.dto.SkillOptionItem;
import com.agenthub.skill.dto.SkillPackageItem;
import com.agenthub.skill.dto.SkillUploadUrl;
import com.agenthub.skill.service.SkillPackageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillPackageService skillPackageService;

    public SkillController(SkillPackageService skillPackageService) {
        this.skillPackageService = skillPackageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('skill:list')")
    public ApiResponse<List<SkillPackageItem>> listSkills() {
        return ApiResponse.success(skillPackageService.list());
    }

    @GetMapping("/available")
    public ApiResponse<List<SkillOptionItem>> listAvailableSkills() {
        return ApiResponse.success(skillPackageService.listAvailable());
    }

    @PostMapping("/upload-url")
    @PreAuthorize("hasAuthority('skill:upload')")
    public ApiResponse<SkillUploadUrl> createUploadUrl(
            Authentication authentication,
            @Valid @RequestBody CreateSkillUploadRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(skillPackageService.createUploadUrl(loginUser.userId(), new CreateSkillUploadCommand(
                request.filename(),
                request.contentType(),
                request.fileSize()
        )));
    }

    @PostMapping("/imports/{importId}/confirm")
    @PreAuthorize("hasAuthority('skill:upload')")
    public ApiResponse<SkillImportPreview> confirmImport(
            Authentication authentication,
            @PathVariable @Positive(message = "导入记录必须为正数") Long importId
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(skillPackageService.confirmImport(loginUser.userId(), importId));
    }

    @PostMapping("/imports/{importId}/apply")
    @PreAuthorize("hasAuthority('skill:upload')")
    public ApiResponse<SkillPackageItem> applyImport(
            Authentication authentication,
            @PathVariable @Positive(message = "导入记录必须为正数") Long importId,
            @Valid @RequestBody ApplySkillImportRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(skillPackageService.applyImport(loginUser.userId(), importId, new SkillImportApplyCommand(
                request.mode(),
                request.name()
        )));
    }

    @GetMapping("/{skillId}/files")
    @PreAuthorize("hasAuthority('skill:list')")
    public ApiResponse<List<SkillFileItem>> listFiles(
            @PathVariable @Positive(message = "Skill 必须为正数") Long skillId
    ) {
        return ApiResponse.success(skillPackageService.files(skillId));
    }

    @GetMapping("/{skillId}/files/content")
    @PreAuthorize("hasAuthority('skill:list')")
    public ApiResponse<SkillFileContent> getFileContent(
            @PathVariable @Positive(message = "Skill 必须为正数") Long skillId,
            @RequestParam("path") String path
    ) {
        return ApiResponse.success(skillPackageService.fileContent(skillId, path));
    }

    @DeleteMapping("/{skillId}")
    @PreAuthorize("hasAuthority('skill:delete')")
    public ApiResponse<Void> deleteSkill(
            @PathVariable @Positive(message = "Skill 必须为正数") Long skillId
    ) {
        skillPackageService.delete(skillId);
        return ApiResponse.success();
    }
}
