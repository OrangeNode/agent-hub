package com.agenthub.storage.object;

import java.time.Duration;

public interface ObjectStorageClient {

    String presignPut(String objectKey, String contentType, Duration ttl);

    String presignGet(String objectKey, Duration ttl);

    ObjectMetadata stat(String objectKey);

    byte[] readFirstBytes(String objectKey, int length);

    byte[] readBytes(String objectKey);

    void delete(String objectKey);
}
