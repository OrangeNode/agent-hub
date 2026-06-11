package com.agenthub.system.operationlog.controller;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.system.operationlog.command.OperationLogQuery;
import com.agenthub.system.operationlog.dto.OperationLogItem;
import com.agenthub.system.operationlog.service.OperationLogService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/operation-logs")
@PreAuthorize("hasAuthority('operation-log:view')")
public class OperationLogController {

    private final OperationLogService operationLogService;

    public OperationLogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    /**
     * 查询后台操作日志列表。
     */
    @GetMapping
    public ApiResponse<List<OperationLogItem>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endAt,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(operationLogService.list(new OperationLogQuery(
                startAt,
                endAt,
                username,
                module,
                action,
                status,
                targetId,
                keyword,
                limit
        )));
    }
}
