package com.agenthub.system.operationlog.command;

import java.time.OffsetDateTime;

public record OperationLogQuery(
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String username,
        String module,
        String action,
        String status,
        String targetId,
        String keyword,
        Integer limit
) {
}
