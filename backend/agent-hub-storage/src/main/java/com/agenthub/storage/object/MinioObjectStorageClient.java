package com.agenthub.storage.object;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.storage.config.StorageProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

public class MinioObjectStorageClient implements ObjectStorageClient {

    private static final String STORAGE_ERROR_MESSAGE = "对象存储操作失败";
    private static final String OBJECT_NOT_FOUND_MESSAGE = "对象不存在";

    private final MinioClient internalClient;
    private final MinioClient presignClient;
    private final StorageProperties properties;

    public MinioObjectStorageClient(MinioClient internalClient, MinioClient presignClient, StorageProperties properties) {
        this.internalClient = internalClient;
        this.presignClient = presignClient;
        this.properties = properties;
    }

    @Override
    public String presignPut(String objectKey, String contentType, Duration ttl) {
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds())
                    .extraHeaders(Map.of("Content-Type", contentType))
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
        }
    }

    @Override
    public String presignGet(String objectKey, Duration ttl) {
        return presignGet(properties.getBucket(), objectKey, ttl);
    }

    @Override
    public String presignGet(String bucket, String objectKey, Duration ttl) {
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(resolveBucket(bucket))
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds())
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
        }
    }

    @Override
    public ObjectMetadata stat(String objectKey) {
        try {
            StatObjectResponse response = internalClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
            return new ObjectMetadata(response.size(), response.contentType());
        } catch (Exception exception) {
            throw storageException(exception);
        }
    }

    @Override
    public byte[] readFirstBytes(String objectKey, int length) {
        return readFirstBytes(properties.getBucket(), objectKey, length);
    }

    @Override
    public byte[] readFirstBytes(String bucket, String objectKey, int length) {
        try (InputStream inputStream = internalClient.getObject(GetObjectArgs.builder()
                .bucket(resolveBucket(bucket))
                .object(objectKey)
                .build())) {
            return inputStream.readNBytes(length);
        } catch (Exception exception) {
            throw storageException(exception);
        }
    }

    @Override
    public byte[] readBytes(String objectKey) {
        return readBytes(properties.getBucket(), objectKey);
    }

    @Override
    public byte[] readBytes(String bucket, String objectKey) {
        try (InputStream inputStream = internalClient.getObject(GetObjectArgs.builder()
                .bucket(resolveBucket(bucket))
                .object(objectKey)
                .build())) {
            return inputStream.readAllBytes();
        } catch (Exception exception) {
            throw storageException(exception);
        }
    }

    @Override
    public void putBytes(String bucket, String objectKey, String contentType, byte[] bytes) {
        byte[] content = bytes == null ? new byte[0] : bytes;
        String resolvedContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            internalClient.putObject(PutObjectArgs.builder()
                    .bucket(resolveBucket(bucket))
                    .object(objectKey)
                    .contentType(resolvedContentType)
                    .stream(inputStream, content.length, -1)
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            internalClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
        }
    }

    private BusinessException storageException(Exception exception) {
        if (isObjectNotFound(exception)) {
            return new BusinessException(ErrorCode.BAD_REQUEST, OBJECT_NOT_FOUND_MESSAGE);
        }
        return new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
    }

    private boolean isObjectNotFound(Exception exception) {
        if (!(exception instanceof ErrorResponseException errorResponseException)
                || errorResponseException.errorResponse() == null) {
            return false;
        }
        String code = errorResponseException.errorResponse().code();
        return "NoSuchKey".equals(code)
                || "NoSuchObject".equals(code)
                || "NoSuchBucket".equals(code)
                || "NotFound".equals(code);
    }

    private String resolveBucket(String bucket) {
        return bucket == null || bucket.isBlank() ? properties.getBucket() : bucket;
    }
}
