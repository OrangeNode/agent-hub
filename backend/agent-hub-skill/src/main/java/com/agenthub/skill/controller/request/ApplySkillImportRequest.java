package com.agenthub.skill.controller.request;

import jakarta.validation.constraints.Size;

public record ApplySkillImportRequest(
        String mode,

        @Size(max = 80, message = "Skill 名称不能超过 80 个字符")
        String name
) {
}
