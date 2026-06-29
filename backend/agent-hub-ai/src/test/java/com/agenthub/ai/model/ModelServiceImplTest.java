package com.agenthub.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.ai.model.command.CreateModelCommand;
import com.agenthub.ai.model.command.UpdateModelCommand;
import com.agenthub.ai.model.controller.request.CreateModelRequest;
import com.agenthub.ai.model.dto.AiModelItem;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.ai.model.entity.AiModel;
import com.agenthub.ai.model.mapper.AiModelMapper;
import com.agenthub.ai.model.service.impl.AiModelServiceImpl;
import com.agenthub.ai.provider.dto.AiProviderItem;
import com.agenthub.ai.provider.service.AiProviderService;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

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
    void shouldCreateModelWithImageInputConfiguration() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(providerService.requireById(1L)).thenReturn(providerItem(1L));
        when(mapper.selectCount(any())).thenReturn(0L);
        CreateModelCommand command = new CreateModelCommand(
                1L,
                "gpt-4o",
                "GPT-4o",
                "MULTIMODAL",
                true,
                true,
                128000,
                4096,
                true,
                true,
                1,
                true,
                1500
        );

        service.create(command);

        ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getImageInputSupported()).isTrue();
        assertThat(captor.getValue().getImageTokenEstimate()).isEqualTo(1500);
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

    @Test
    void shouldRequireAvailableRuntimeConfig() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectRuntimeConfigById(1L)).thenReturn(runtimeConfig(1L, true, true));

        AiModelRuntimeConfig config = service.requireAvailableRuntimeConfig(1L);

        assertThat(config.modelId()).isEqualTo(1L);
        assertThat(config.providerCode()).isEqualTo("openai");
        assertThat(config.apiKeyCipher()).isEqualTo("sk-cipher");
    }

    @Test
    void shouldRequireDefaultRuntimeConfig() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectDefaultRuntimeConfig()).thenReturn(runtimeConfig(1L, true, true));

        AiModelRuntimeConfig config = service.requireDefaultRuntimeConfig();

        assertThat(config.modelId()).isEqualTo(1L);
        assertThat(config.providerCode()).isEqualTo("openai");
        verify(mapper).selectDefaultRuntimeConfig();
    }

    @Test
    void shouldRejectDefaultRuntimeConfigWhenProviderDisabled() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectDefaultRuntimeConfig()).thenReturn(runtimeConfig(1L, true, false));

        assertThatThrownBy(service::requireDefaultRuntimeConfig)
                .isInstanceOf(BusinessException.class)
                .hasMessage("供应商不可用")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectRuntimeConfigWhenModelDisabled() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectRuntimeConfigById(1L)).thenReturn(runtimeConfig(1L, false, true));

        assertThatThrownBy(() -> service.requireAvailableRuntimeConfig(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模型不可用")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectRuntimeConfigWhenModelNotFound() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectRuntimeConfigById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.requireAvailableRuntimeConfig(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模型不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldRejectRuntimeConfigWhenProviderDisabled() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectRuntimeConfigById(1L)).thenReturn(runtimeConfig(1L, true, false));

        assertThatThrownBy(() -> service.requireAvailableRuntimeConfig(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("供应商不可用")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldConvertDuplicateKeyExceptionWhenCreateModel() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(providerService.requireById(1L)).thenReturn(providerItem(1L));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(org.mockito.ArgumentMatchers.<AiModel>any()))
                .thenThrow(new DuplicateKeyException("uk_ai_model_provider_code"));

        assertThatThrownBy(() -> service.create(createCommand(1L, "gpt-4o", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模型编码已存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldConvertDuplicateKeyExceptionWhenUpdateModel() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(mapper.selectById(1L)).thenReturn(model(1L, 1L, "gpt-4o"));
        when(providerService.requireById(1L)).thenReturn(providerItem(1L));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.updateById(org.mockito.ArgumentMatchers.<AiModel>any()))
                .thenThrow(new DuplicateKeyException("uk_ai_model_provider_code"));

        assertThatThrownBy(() -> service.update(1L, updateCommand(1L, "gpt-4o-mini", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模型编码已存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectDefaultModelWhenModelDisabled() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(providerService.requireById(1L)).thenReturn(providerItem(1L));
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.create(new CreateModelCommand(
                1L,
                "gpt-4o",
                "GPT-4o",
                "CHAT",
                true,
                false,
                128000,
                4096,
                true,
                false,
                0,
                false,
                null
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("默认模型必须处于可用状态")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectDefaultModelWhenProviderDisabled() {
        AiModelMapper mapper = mock(AiModelMapper.class);
        AiProviderService providerService = mock(AiProviderService.class);
        AiModelServiceImpl service = new AiModelServiceImpl(mapper, providerService);
        when(providerService.requireById(1L)).thenReturn(new AiProviderItem(
                1L,
                "openai",
                "OpenAI",
                "LLM",
                "OPENAI",
                "https://api.openai.com/v1",
                "BEARER",
                "v1",
                "{}",
                "{}",
                true,
                false,
                0
        ));
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.create(createCommand(1L, "gpt-4o", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("供应商不可用")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldValidateCreateModelRequestNumberAndCapabilityLimits() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            CreateModelRequest request = new CreateModelRequest(
                    0L,
                    "gpt-4o",
                    "GPT-4o",
                    "c".repeat(65),
                    true,
                    false,
                    -1,
                    -2,
                    false,
                    true,
                    -1,
                    true,
                    0
            );

            assertThat(validator.validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("providerId", "capabilityType", "contextWindow", "maxOutputTokens", "sort",
                            "imageTokenEstimate");
        }
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
                null,
                false,
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
                1,
                false,
                null
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
                "{}",
                "{}",
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
                false,
                null,
                128000,
                4096,
                defaultModel,
                enabled,
                0
        );
    }

    private AiModelRuntimeConfig runtimeConfig(Long modelId, boolean modelEnabled, boolean providerEnabled) {
        return new AiModelRuntimeConfig(
                modelId,
                1L,
                "openai",
                "OpenAI",
                "LLM",
                "OPENAI",
                "https://api.openai.com/v1",
                "sk-cipher",
                "BEARER",
                "v1",
                "{}",
                "{}",
                "gpt-4o",
                "GPT-4o",
                true,
                false,
                false,
                null,
                128000,
                4096,
                modelEnabled,
                providerEnabled
        );
    }
}
