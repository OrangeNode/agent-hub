package com.agenthub.storage.config;

import com.agenthub.storage.object.MinioObjectStorageClient;
import com.agenthub.storage.object.ObjectStorageClient;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean
    public MinioClient internalMinioClient(StorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean
    public MinioClient presignMinioClient(StorageProperties properties) {
        String endpoint = properties.getPublicEndpoint() == null || properties.getPublicEndpoint().isBlank()
                ? properties.getEndpoint()
                : properties.getPublicEndpoint();
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean
    public ObjectStorageClient objectStorageClient(
            @Qualifier("internalMinioClient") MinioClient internalMinioClient,
            @Qualifier("presignMinioClient") MinioClient presignMinioClient,
            StorageProperties properties
    ) {
        return new MinioObjectStorageClient(internalMinioClient, presignMinioClient, properties);
    }
}
