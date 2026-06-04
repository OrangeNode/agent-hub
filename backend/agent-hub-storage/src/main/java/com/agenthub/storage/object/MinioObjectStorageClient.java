package com.agenthub.storage.object;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.storage.config.StorageProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import java.time.Duration;
import java.util.Map;

public class MinioObjectStorageClient implements ObjectStorageClient {

    private static final String STORAGE_ERROR_MESSAGE = "对象存储操作失败";

    private final MinioClient minioClient;
    private final StorageProperties properties;

    public MinioObjectStorageClient(MinioClient minioClient, StorageProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public String presignPut(String objectKey, String contentType, Duration ttl) {
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds())
                    .extraHeaders(Map.of("Content-Type", contentType))
                    .build());
            return replacePublicEndpoint(url);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
        }
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds())
                    .build());
            return replacePublicEndpoint(url);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
        }
    }

    @Override
    public ObjectMetadata stat(String objectKey) {
        try {
            StatObjectResponse response = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
            return new ObjectMetadata(response.size(), response.contentType());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
        }
    }

    private String replacePublicEndpoint(String url) {
        if (properties.getPublicEndpoint() == null || properties.getPublicEndpoint().isBlank()
                || properties.getEndpoint() == null || properties.getEndpoint().isBlank()) {
            return url;
        }
        return url.replaceFirst("^" + java.util.regex.Pattern.quote(properties.getEndpoint()), properties.getPublicEndpoint());
    }
}
