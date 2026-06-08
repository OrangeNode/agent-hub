package com.agenthub.sandbox.execution.mapper;

import com.agenthub.sandbox.execution.entity.SandboxExecution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SandboxExecutionMapper extends BaseMapper<SandboxExecution> {
}
