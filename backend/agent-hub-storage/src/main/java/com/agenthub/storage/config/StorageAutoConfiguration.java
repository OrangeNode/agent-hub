package com.agenthub.storage.config;

import com.agenthub.storage.object.MinioObjectStorageClient;
import com.agenthub.storage.object.ObjectStorageClient;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean
    public MinioClient minioClient(StorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean
    public ObjectStorageClient objectStorageClient(MinioClient minioClient, StorageProperties properties) {
        return new MinioObjectStorageClient(minioClient, properties);
    }
}
