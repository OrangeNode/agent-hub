package com.agenthub.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.command.UpdateModelCommand;
import com.agenthub.ai.model.dto.AiModelItem;
import com.agenthub.ai.model.entity.AiModel;
import com.agenthub.ai.model.mapper.AiModelMapper;
import com.agenthub.ai.model.service.impl.AiModelServiceImpl;
import com.agenthub.ai.provider.dto.AiProviderItem;
import com.agenthub.ai.provider.service.AiProviderService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ModelServiceImplTest {

    @Test
    void shouldRejectCreateWhenProviderNotFound() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(providerService.requireById(99L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "供应商不存在"));

        assertThatThrownBy(() -> service.create(createCommand(99L, "gpt-4o", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("供应商不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldRejectDuplicateModelCodeInSameProvider() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(providerService.requireById(1L)).thenReturn(providerItem(1L));
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(createCommand(1L, "gpt-4o", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模型编码已存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldClearOtherDefaultModelsWhenCreateDefaultModel() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        ArgumentCaptor<AiModel> modelCaptor = ArgumentCaptor.forClass(AiModel.class);
        when(providerService.requireById(1L)).thenReturn(providerItem(1L));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(modelCaptor.capture())).thenAnswer(invocation -> {
            modelCaptor.getValue().setId(10L);
            return 1;
        });
        when(mapper.selectJoinedById(10L)).thenReturn(modelItem(10L, 1L, true, true));

        AiModelItem item = service.create(createCommand(1L, "gpt-4o", true));

        assertThat(item.modelId()).isEqualTo(10L);
        verify(mapper).clearDefaultModelExcept(10L);
    }

    @Test
    void shouldListAvailableModelsFromEnabledProvidersAndEnabledModels() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectAvailableItems()).thenReturn(List.of(
                modelItem(1L, 1L, false, true),
                modelItem(2L, 2L, true, true)
        ));

        List<AiModelItem> items = service.listAvailable();

        assertThat(items)
                .extracting(AiModelItem::modelId)
                .containsExactly(1L, 2L);
        verify(mapper).selectAvailableItems();
    }

    @Test
    void shouldRejectUpdateWhenAnotherModelHasSameCodeInSameProvider() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectById(1L)).thenReturn(model(1L, 1L, "gpt-4o"));
        when(providerService.requireById(1L)).thenReturn(providerItem(1L));
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.update(1L, updateCommand(1L, "gpt-4o-mini", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模型编码已存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectDeletingNonexistentModel() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模型不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    private CreateModelCommand createCommand(Long providerId, String modelCode, Boolean defaultModel) {
        return new CreateModelCommand(
                providerId,
                modelCode,
                "GPT-4o",
                "CHAT",
                null,
                null,
                128000,
                4096,
                defaultModel,
                null,
                null
        );
    }

    private UpdateModelCommand updateCommand(Long providerId, String modelCode, Boolean defaultModel) {
        return new UpdateModelCommand(
                providerId,
                modelCode,
                "GPT-4o Mini",
                "CHAT",
                true,
                false,
                128000,
                4096,
                defaultModel,
                true,
                1
        );
    }

    private AiProviderItem providerItem(Long providerId) {
        return new AiProviderItem(
                providerId,
                "openai",
                "OpenAI",
                "LLM",
                "OPENAI",
                "https://api.openai.com/v1",
                "BEARER",
                "v1",
                true,
                true,
                0
        );
    }

    private AiModel model(Long modelId, Long providerId, String modelCode) {
        AiModel model = new AiModel();
        model.setId(modelId);
        model.setProviderId(providerId);
        model.setModelCode(modelCode);
        model.setDisplayName("GPT-4o");
        model.setCapabilityType("CHAT");
        model.setStreamSupported(true);
        model.setReasoningSupported(false);
        model.setContextWindow(128000);
        model.setMaxOutputTokens(4096);
        model.setDefaultModel(false);
        model.setEnabled(true);
        model.setSort(0);
        return model;
    }

    private AiModelItem modelItem(Long modelId, Long providerId, Boolean defaultModel, Boolean enabled) {
        return new AiModelItem(
                modelId,
                providerId,
                "openai",
                "OpenAI",
                "gpt-4o",
                "GPT-4o",
                "CHAT",
                true,
                false,
                128000,
                4096,
                defaultModel,
                enabled,
                0
        );
    }
}
