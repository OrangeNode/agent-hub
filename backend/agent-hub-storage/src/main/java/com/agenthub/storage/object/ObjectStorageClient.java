package com.agenthub.storage.object;

import java.time.Duration;

public interface ObjectStorageClient {

    String presignPut(String objectKey, String contentType, Duration ttl);

    String presignGet(String objectKey, Duration ttl);

    default String presignGet(String bucket, String objectKey, Duration ttl) {
        return presignGet(objectKey, ttl);
    }

    ObjectMetadata stat(String objectKey);

    byte[] readFirstBytes(String objectKey, int length);

    default byte[] readFirstBytes(String bucket, String objectKey, int length) {
        return readFirstBytes(objectKey, length);
    }

    byte[] readBytes(String objectKey);

    default byte[] readBytes(String bucket, String objectKey) {
        return readBytes(objectKey);
    }

    void putBytes(String bucket, String objectKey, String contentType, byte[] bytes);

    default void putBytes(String objectKey, String contentType, byte[] bytes) {
        putBytes(null, objectKey, contentType, bytes);
    }

    void delete(String objectKey);
}
