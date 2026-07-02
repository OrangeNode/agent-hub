package com.agenthub.storage.object;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.storage.config.StorageProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
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
    void shouldPresignPutToExplicitBucket() throws Exception {
        when(presignClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://storage.example.com/skill.zip");

        String url = client.presignPut("agent-hub-skill-packages", "skill-packages/demo.zip",
                "application/zip", java.time.Duration.ofMinutes(10));

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(presignClient).getPresignedObjectUrl(captor.capture());
        assertThat(url).isEqualTo("https://storage.example.com/skill.zip");
        assertThat(captor.getValue().bucket()).isEqualTo("agent-hub-skill-packages");
        assertThat(captor.getValue().object()).isEqualTo("skill-packages/demo.zip");
    }

    @Test
    void shouldUseDerivedEndpointClientWhenPresignEndpointChanges() throws Exception {
        MinioClient derivedPresignClient = mock(MinioClient.class);
        AtomicReference<String> derivedEndpoint = new AtomicReference<>();
        MinioObjectStorageClient dynamicClient = new MinioObjectStorageClient(
                internalClient,
                presignClient,
                storageProperties(),
                "http://127.0.0.1:9000",
                () -> "http://agent.example.com:9000",
                endpoint -> {
                    derivedEndpoint.set(endpoint);
                    return derivedPresignClient;
                }
        );
        when(derivedPresignClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://agent.example.com:9000/agent-hub/upload");

        String url = dynamicClient.presignPut("agent-hub", "upload", "text/plain", Duration.ofMinutes(10));

        assertThat(url).isEqualTo("http://agent.example.com:9000/agent-hub/upload");
        assertThat(derivedEndpoint.get()).isEqualTo("http://agent.example.com:9000");
        verify(derivedPresignClient).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        verify(presignClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

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
