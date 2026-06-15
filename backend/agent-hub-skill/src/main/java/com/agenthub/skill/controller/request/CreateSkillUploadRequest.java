package com.agenthub.skill.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateSkillUploadRequest(
        @NotBlank(message = "文件名不能为空")
        @Size(max = 255, message = "文件名不能超过 255 个字符")
        String filename,

        @Size(max = 100, message = "文件类型不能超过 100 个字符")
        String contentType,

        @Positive(message = "文件大小必须为正数")
        long fileSize
) {
}
