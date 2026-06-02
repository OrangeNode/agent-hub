package com.agenthub.ai.provider.service;

import com.agenthub.ai.provider.command.CreateProviderCommand;
import com.agenthub.ai.provider.command.UpdateProviderCommand;
import com.agenthub.ai.provider.dto.AiProviderItem;
import java.util.List;

public interface AiProviderService {

    List<AiProviderItem> list();

    AiProviderItem requireById(Long providerId);

    AiProviderItem create(CreateProviderCommand command);

    AiProviderItem update(Long providerId, UpdateProviderCommand command);

    void delete(Long providerId);
}
