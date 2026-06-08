package com.agenthub.storage.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.security.principal.LoginUser;
import com.agenthub.storage.attachment.controller.AttachmentController;
import com.agenthub.storage.attachment.controller.request.CreateImageUploadUrlRequest;
import com.agenthub.storage.attachment.dto.AttachmentUploadUrl;
import com.agenthub.storage.attachment.service.AttachmentService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AttachmentControllerTest {

    @Test
    void shouldCreateGenericAttachmentUploadUrlForLoginUser() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        AttachmentController controller = new AttachmentController(attachmentService);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new LoginUser(7L, "orange", List.of()));
        AttachmentUploadUrl expected = new AttachmentUploadUrl(
                10L,
                "http://minio/upload",
                "PUT",
                Map.of("Content-Type", "text/csv"),
                OffsetDateTime.now().plusMinutes(10)
        );
        when(attachmentService.createAttachmentUploadUrl(7L, "data.csv", "text/csv", 1024L))
                .thenReturn(expected);

        AttachmentUploadUrl actual = controller.createAttachmentUploadUrl(
                authentication,
                new CreateImageUploadUrlRequest("data.csv", "text/csv", 1024L)
        ).data();

        assertThat(actual).isEqualTo(expected);
        verify(attachmentService).createAttachmentUploadUrl(7L, "data.csv", "text/csv", 1024L);
    }
}
