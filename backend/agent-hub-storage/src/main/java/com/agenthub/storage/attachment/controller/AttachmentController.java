package com.agenthub.storage.attachment.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.storage.attachment.controller.request.CreateImageUploadUrlRequest;
import com.agenthub.storage.attachment.dto.AttachmentUploadUrl;
import com.agenthub.storage.attachment.service.AttachmentService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping("/image-upload-url")
    public ApiResponse<AttachmentUploadUrl> createImageUploadUrl(
            Authentication authentication,
            @Valid @RequestBody CreateImageUploadUrlRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(attachmentService.createImageUploadUrl(
                loginUser.userId(),
                request.filename(),
                request.contentType(),
                request.fileSize()
        ));
    }

    @PostMapping("/{attachmentId}/confirm")
    public ApiResponse<Void> confirmUpload(Authentication authentication, @PathVariable Long attachmentId) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        attachmentService.confirmUpload(loginUser.userId(), attachmentId);
        return ApiResponse.success();
    }
}
