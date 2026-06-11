package com.agenthub.system.operationlog.service.impl;

import com.agenthub.system.operationlog.command.OperationLogQuery;
import com.agenthub.system.operationlog.dto.OperationLogItem;
import com.agenthub.system.operationlog.entity.OperationLogRecord;
import com.agenthub.system.operationlog.mapper.OperationLogMapper;
import com.agenthub.system.operationlog.service.OperationLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

@Service
public class OperationLogServiceImpl implements OperationLogService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final OperationLogMapper operationLogMapper;

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(OperationLogRecord record) {
        operationLogMapper.insert(record);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperationLogItem> list(OperationLogQuery query) {
        OperationLogQuery safeQuery = query == null
                ? new OperationLogQuery(null, null, null, null, null, null, null, null, null)
                : query;
        LambdaQueryWrapper<OperationLogRecord> wrapper = new LambdaQueryWrapper<>();

        wrapper.ge(safeQuery.startAt() != null, OperationLogRecord::getCreatedAt, safeQuery.startAt());
        wrapper.le(safeQuery.endAt() != null, OperationLogRecord::getCreatedAt, safeQuery.endAt());
        wrapper.eq(StringUtils.hasText(safeQuery.username()), OperationLogRecord::getUsername, safeQuery.username());
        wrapper.eq(StringUtils.hasText(safeQuery.module()), OperationLogRecord::getModule, safeQuery.module());
        wrapper.eq(StringUtils.hasText(safeQuery.action()), OperationLogRecord::getAction, safeQuery.action());
        wrapper.eq(StringUtils.hasText(safeQuery.status()), OperationLogRecord::getStatus, safeQuery.status());
        wrapper.eq(StringUtils.hasText(safeQuery.targetId()), OperationLogRecord::getTargetId, safeQuery.targetId());
        if (StringUtils.hasText(safeQuery.keyword())) {
            String keyword = safeQuery.keyword();
            wrapper.and(nested -> nested
                    .like(OperationLogRecord::getModule, keyword)
                    .or().like(OperationLogRecord::getAction, keyword)
                    .or().like(OperationLogRecord::getTargetId, keyword)
                    .or().like(OperationLogRecord::getReason, keyword)
                    .or().like(OperationLogRecord::getRequestUri, keyword)
                    .or().like(OperationLogRecord::getParamsJson, keyword)
                    .or().like(OperationLogRecord::getResultJson, keyword)
                    .or().like(OperationLogRecord::getErrorMessage, keyword)
            );
        }
        wrapper.orderByDesc(OperationLogRecord::getCreatedAt)
                .orderByDesc(OperationLogRecord::getId)
                .last("LIMIT " + normalizeLimit(safeQuery.limit()));

        return operationLogMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private OperationLogItem toItem(OperationLogRecord record) {
        return new OperationLogItem(
                record.getId(),
                record.getTraceId(),
                record.getUserId(),
                record.getUsername(),
                record.getModule(),
                record.getAction(),
                record.getTargetType(),
                record.getTargetId(),
                record.getOperationType(),
                record.getReason(),
                record.getRequestMethod(),
                record.getRequestUri(),
                record.getClientIp(),
                record.getUserAgent(),
                record.getParamsJson(),
                record.getResultJson(),
                record.getStatus(),
                record.getErrorCode(),
                record.getErrorMessage(),
                record.getDurationMs(),
                record.getCreatedAt()
        );
    }
}
