package com.agenthub.ai.model.service;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.command.UpdateModelCommand;
import com.agenthub.ai.model.dto.AiModelItem;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import java.util.List;

public interface AiModelService {

    List<AiModelItem> list();

    List<AiModelItem> listAvailable();

    AiModelRuntimeConfig requireAvailableRuntimeConfig(Long modelId);

    AiModelRuntimeConfig requireDefaultRuntimeConfig();

    AiModelItem create(CreateModelCommand command);

    AiModelItem update(Long modelId, UpdateModelCommand command);

    void delete(Long modelId);
}
