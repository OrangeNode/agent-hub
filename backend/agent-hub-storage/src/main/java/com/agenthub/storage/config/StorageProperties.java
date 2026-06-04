package com.agenthub.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent-hub.storage")
public class StorageProperties {

    private String endpoint;
    private String publicEndpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private int imageExpireDays = 7;
    private int presignedUploadTtlMinutes = 10;
    private int presignedReadTtlMinutes = 10;
    private int pendingCleanupMinutes = 60;
    private int unusedReadyCleanupHours = 24;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getPublicEndpoint() {
        return publicEndpoint;
    }

    public void setPublicEndpoint(String publicEndpoint) {
        this.publicEndpoint = publicEndpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public int getImageExpireDays() {
        return imageExpireDays;
    }

    public void setImageExpireDays(int imageExpireDays) {
        this.imageExpireDays = imageExpireDays;
    }

    public int getPresignedUploadTtlMinutes() {
        return presignedUploadTtlMinutes;
    }

    public void setPresignedUploadTtlMinutes(int presignedUploadTtlMinutes) {
        this.presignedUploadTtlMinutes = presignedUploadTtlMinutes;
    }

    public int getPresignedReadTtlMinutes() {
        return presignedReadTtlMinutes;
    }

    public void setPresignedReadTtlMinutes(int presignedReadTtlMinutes) {
        this.presignedReadTtlMinutes = presignedReadTtlMinutes;
    }

    public int getPendingCleanupMinutes() {
        return pendingCleanupMinutes;
    }

    public void setPendingCleanupMinutes(int pendingCleanupMinutes) {
        this.pendingCleanupMinutes = pendingCleanupMinutes;
    }

    public int getUnusedReadyCleanupHours() {
        return unusedReadyCleanupHours;
    }

    public void setUnusedReadyCleanupHours(int unusedReadyCleanupHours) {
        this.unusedReadyCleanupHours = unusedReadyCleanupHours;
    }
}
