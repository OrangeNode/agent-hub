package com.agenthub.ai.provider.service;

import com.agenthub.ai.provider.dto.AiProviderConnectionTestResult;
import com.agenthub.ai.provider.entity.AiProvider;

public interface AiProviderConnectivityTester {

    /**
     * 使用已保存的供应商配置发起轻量连通性探测。
     */
    AiProviderConnectionTestResult test(AiProvider provider);
}
