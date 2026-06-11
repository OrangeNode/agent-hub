package com.agenthub.system.operationlog.dto;

import java.time.OffsetDateTime;

public record OperationLogItem(
        Long id,
        String traceId,
        Long userId,
        String username,
        String module,
        String action,
        String targetType,
        String targetId,
        String operationType,
        String reason,
        String requestMethod,
        String requestUri,
        String clientIp,
        String userAgent,
        String paramsJson,
        String resultJson,
        String status,
        String errorCode,
        String errorMessage,
        Long durationMs,
        OffsetDateTime createdAt
) {
}
