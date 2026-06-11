package com.agenthub.system.operationlog.mapper;

import com.agenthub.system.operationlog.entity.OperationLogRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogRecord> {
}
