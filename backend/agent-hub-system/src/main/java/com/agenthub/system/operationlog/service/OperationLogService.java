package com.agenthub.system.operationlog.service;

import com.agenthub.system.operationlog.command.OperationLogQuery;
import com.agenthub.system.operationlog.dto.OperationLogItem;
import com.agenthub.system.operationlog.entity.OperationLogRecord;
import java.util.List;

public interface OperationLogService {

    void record(OperationLogRecord record);

    List<OperationLogItem> list(OperationLogQuery query);
}
