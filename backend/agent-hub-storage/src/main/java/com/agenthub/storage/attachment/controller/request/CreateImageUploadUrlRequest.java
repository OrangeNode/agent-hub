package com.agenthub.storage.attachment.controller.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateImageUploadUrlRequest(
        @NotBlank(message = "文件名不能为空")
        String filename,

        @NotBlank(message = "文件类型不能为空")
        String contentType,

        @NotNull(message = "文件大小不能为空")
        @Min(value = 1, message = "文件大小必须大于 0")
        Long fileSize
) {
}
