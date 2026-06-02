package com.agenthub.ai.model.service;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.command.UpdateModelCommand;
import com.agenthub.ai.model.dto.AiModelItem;
import java.util.List;

public interface AiModelService {

    List<AiModelItem> list();

    List<AiModelItem> listAvailable();

    AiModelItem create(CreateModelCommand command);

    AiModelItem update(Long modelId, UpdateModelCommand command);

    void delete(Long modelId);
}
