package com.agenthub.storage.object;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenthub.storage.config.StorageProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MinioObjectStorageClientTest {

    private final MinioClient internalClient = mock(MinioClient.class);
    private final MinioClient presignClient = mock(MinioClient.class);
    private final MinioObjectStorageClient client = new MinioObjectStorageClient(
            internalClient,
            presignClient,
            storageProperties()
    );

    @Test
    void shouldDefaultContentTypeWhenNullContentType() throws Exception {
        client.putBytes("custom-bucket", "skill/file.md", null, "content".getBytes(StandardCharsets.UTF_8));

        PutObjectArgs args = capturePutObjectArgs();
        assertThat(args.bucket()).isEqualTo("custom-bucket");
        assertThat(args.object()).isEqualTo("skill/file.md");
        assertThat(args.contentType()).isEqualTo("application/octet-stream");
        assertThat(args.stream().readAllBytes()).containsExactly("content".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldDefaultContentTypeWhenBlankContentType() throws Exception {
        client.putBytes("custom-bucket", "skill/file.md", " ", "content".getBytes(StandardCharsets.UTF_8));

        PutObjectArgs args = capturePutObjectArgs();
        assertThat(args.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void shouldUploadEmptyBytesToExplicitBucket() throws Exception {
        client.putBytes("custom-bucket", "skill/empty.txt", "text/plain", new byte[0]);

        PutObjectArgs args = capturePutObjectArgs();
        assertThat(args.bucket()).isEqualTo("custom-bucket");
        assertThat(args.object()).isEqualTo("skill/empty.txt");
        assertThat(args.contentType()).isEqualTo("text/plain");
        assertThat(args.stream().readAllBytes()).isEmpty();
    }

    private PutObjectArgs capturePutObjectArgs() throws Exception {
        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(internalClient).putObject(captor.capture());
        return captor.getValue();
    }

    private StorageProperties storageProperties() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("agent-hub");
        return properties;
    }
}
