package com.agenthub.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.ai.provider.command.CreateProviderCommand;
import com.agenthub.ai.provider.command.UpdateProviderCommand;
import com.agenthub.ai.provider.controller.request.CreateProviderRequest;
import com.agenthub.ai.provider.dto.AiProviderConnectionTestResult;
import com.agenthub.ai.provider.dto.AiProviderItem;
import com.agenthub.ai.provider.entity.AiProvider;
import com.agenthub.ai.provider.mapper.AiProviderMapper;
import com.agenthub.ai.provider.service.AiProviderConnectivityTester;
import com.agenthub.ai.provider.service.impl.AiProviderServiceImpl;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProviderServiceImplTest {

    @Test
    void shouldCreateProviderWithApiKeyFlagAndWithoutPlainApiKey() {
        AiProviderMapper mapper = mock(AiProviderMapper.class);
        AiProviderServiceImpl service = service(mapper);
        ArgumentCaptor<AiProvider> providerCaptor = ArgumentCaptor.forClass(AiProvider.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(providerCaptor.capture())).thenAnswer(invocation -> {
            providerCaptor.getValue().setId(1L);
            return 1;
        });
        when(mapper.selectById(1L)).thenAnswer(invocation -> providerCaptor.getValue());

        AiProviderItem item = service.create(new CreateProviderCommand(
                "openai",
                "OpenAI",
                "LLM",
                "OPENAI_COMPATIBLE",
                "https://api.openai.com/v1",
                "sk-test",
                "BEARER",
                "v1",
                "{\"X-Test\":\"1\"}",
                "{\"temperature\":0.7}",
                null,
                null
        ));

        assertThat(item.providerId()).isEqualTo(1L);
        assertThat(item.hasApiKey()).isTrue();
        assertThat(item.extraHeaders()).isEqualTo("{\"X-Test\":\"1\"}");
        assertThat(item.extraBody()).isEqualTo("{\"temperature\":0.7}");
        assertThat(Arrays.stream(item.getClass().getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("apiKey", "apiKeyCipher");
    }

    @Test
    void shouldRejectDuplicateProviderCode() {
        AiProviderMapper mapper = mock(AiProviderMapper.class);
        AiProviderServiceImpl service = service(mapper);
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(new CreateProviderCommand(
                "openai",
                "OpenAI",
                "LLM",
                "OPENAI_COMPATIBLE",
                "https://api.openai.com/v1",
                "sk-test",
                "BEARER",
                "v1",
                "{}",
                "{}",
                true,
                0
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("供应商编码已存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldKeepOldApiKeyCipherWhenUpdateApiKeyIsBlank() {
        AiProviderMapper mapper = mock(AiProviderMapper.class);
        AiProviderServiceImpl service = service(mapper);
        AiProvider existed = provider(1L, "openai", "old-secret");
        ArgumentCaptor<AiProvider> providerCaptor = ArgumentCaptor.forClass(AiProvider.class);
        when(mapper.selectById(1L)).thenReturn(existed, existed);
        when(mapper.updateById(providerCaptor.capture())).thenReturn(1);

        AiProviderItem item = service.update(1L, new UpdateProviderCommand(
                "OpenAI 新名称",
                "LLM",
                "OPENAI_COMPATIBLE",
                "https://api.openai.com/v1",
                "   ",
                "BEARER",
                "v1",
                "{}",
                "{}",
                true,
                10
        ));

        assertThat(providerCaptor.getValue().getApiKeyCipher()).isEqualTo("old-secret");
        assertThat(item.hasApiKey()).isTrue();
    }

    @Test
    void shouldOverwriteOldApiKeyCipherWhenUpdateApiKeyIsNonblank() {
        AiProviderMapper mapper = mock(AiProviderMapper.class);
        AiProviderServiceImpl service = service(mapper);
        AiProvider existed = provider(1L, "openai", "old-secret");
        ArgumentCaptor<AiProvider> providerCaptor = ArgumentCaptor.forClass(AiProvider.class);
        when(mapper.selectById(1L)).thenReturn(existed, existed);
        when(mapper.updateById(providerCaptor.capture())).thenReturn(1);

        AiProviderItem item = service.update(1L, new UpdateProviderCommand(
                "OpenAI 新名称",
                "LLM",
                "OPENAI_COMPATIBLE",
                "https://api.openai.com/v1",
                "new-secret",
                "BEARER",
                "v1",
                "{}",
                "{}",
                true,
                10
        ));

        assertThat(providerCaptor.getValue().getApiKeyCipher()).isEqualTo("new-secret");
        assertThat(item.hasApiKey()).isTrue();
    }

    @Test
    void shouldRejectRemovedProviderProtocol() {
        AiProviderMapper mapper = mock(AiProviderMapper.class);
        AiProviderServiceImpl service = service(mapper);
        when(mapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.create(new CreateProviderCommand(
                "anthropic",
                "Anthropic",
                "LLM",
                "ANTHROPIC_MESSAGES",
                "https://api.anthropic.com/v1",
                "sk-test",
                "BEARER",
                "v1",
                "{}",
                "{}",
                true,
                0
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OPENAI_COMPATIBLE")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectDeletingNonexistentProvider() {
        AiProviderMapper mapper = mock(AiProviderMapper.class);
        AiProviderServiceImpl service = service(mapper);
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("供应商不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldRequireProviderByIdAndReturnItem() {
        AiProviderMapper mapper = mock(AiProviderMapper.class);
        AiProviderServiceImpl service = service(mapper);
        when(mapper.selectById(1L)).thenReturn(provider(1L, "openai", "secret"));

        AiProviderItem item = service.requireById(1L);

        assertThat(item.providerId()).isEqualTo(1L);
        assertThat(item.code()).isEqualTo("openai");
        assertThat(item.hasApiKey()).isTrue();
    }

    @Test
    void shouldTestProviderConnectionWithSavedConfig() {
        AiProviderMapper mapper = mock(AiProviderMapper.class);
        AiProviderConnectivityTester tester = mock(AiProviderConnectivityTester.class);
        AiProvider provider = provider(1L, "openai", "secret");
        AiProviderConnectionTestResult expected = new AiProviderConnectionTestResult(true, "连接成功", 200);
        AiProviderServiceImpl service = new AiProviderServiceImpl(mapper, tester);
        when(mapper.selectById(1L)).thenReturn(provider);
        when(tester.test(provider)).thenReturn(expected);

        AiProviderConnectionTestResult result = service.testConnection(1L);

        assertThat(result).isEqualTo(expected);
        verify(tester).test(provider);
    }

    @Test
    void shouldRejectRequiringNonexistentProvider() {
        AiProviderMapper mapper = mock(AiProviderMapper.class);
        AiProviderServiceImpl service = service(mapper);
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.requireById(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("供应商不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldRejectCreateProviderRequestWhenTextExceedsSizeLimit() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            CreateProviderRequest request = new CreateProviderRequest(
                    "a".repeat(65),
                    "OpenAI",
                    "LLM",
                    "OPENAI_COMPATIBLE",
                    "https://api.example.com/" + "v".repeat(512),
                    "sk-test",
                    "BEARER",
                    "v1",
                    "{}",
                    "{}",
                    true,
                    0
            );

            assertThat(validator.validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("code", "baseUrl");
        }
    }

    private AiProvider provider(Long id, String code, String apiKeyCipher) {
        AiProvider provider = new AiProvider();
        provider.setId(id);
        provider.setCode(code);
        provider.setName("OpenAI");
        provider.setProviderType("LLM");
        provider.setProtocolType("OPENAI_COMPATIBLE");
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setApiKeyCipher(apiKeyCipher);
        provider.setAuthType("BEARER");
        provider.setApiVersion("v1");
        provider.setExtraHeaders("{}");
        provider.setExtraBody("{}");
        provider.setEnabled(true);
        provider.setSort(0);
        return provider;
    }

    private AiProviderServiceImpl service(AiProviderMapper mapper) {
        return new AiProviderServiceImpl(mapper, mock(AiProviderConnectivityTester.class));
    }
}
