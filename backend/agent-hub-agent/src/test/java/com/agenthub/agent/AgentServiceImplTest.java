package com.agenthub.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.agent.command.CreateAgentCommand;
import com.agenthub.agent.command.UpdateAgentCommand;
import com.agenthub.agent.dto.AgentItem;
import com.agenthub.agent.dto.AgentOptionItem;
import com.agenthub.agent.dto.AgentRuntimeConfig;
import com.agenthub.agent.entity.AgentConfig;
import com.agenthub.agent.mapper.AgentConfigMapper;
import com.agenthub.agent.service.impl.AgentServiceImpl;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class AgentServiceImplTest {

    @Test
    void shouldListAvailableAgentsWithoutSystemPrompt() {
        AgentConfigMapper mapper = mock(AgentConfigMapper.class);
        AgentServiceImpl service = new AgentServiceImpl(mapper);
        when(mapper.selectAvailableOptions()).thenReturn(List.of(
                new AgentOptionItem(1L, "official-writing", "公文写作 Agent", "文", "通知、请示、汇报材料起草", 10L, true, 1),
                new AgentOptionItem(2L, "data-analysis", "数据分析 Agent", "数", "表格分析、指标解释、结论提炼", null, false, 2)
        ));

        List<AgentOptionItem> items = service.listAvailable();

        assertThat(items)
                .extracting(AgentOptionItem::agentKey)
                .containsExactly("official-writing", "data-analysis");
        assertThat(items.getFirst().getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("systemPrompt");
    }

    @Test
    void shouldResolveEnabledRuntimeConfigByKey() {
        AgentConfigMapper mapper = mock(AgentConfigMapper.class);
        AgentServiceImpl service = new AgentServiceImpl(mapper);
        when(mapper.selectByAgentKey("official-writing")).thenReturn(agent(1L, true, 10L));

        AgentRuntimeConfig config = service.requireEnabledRuntimeConfig("official-writing");

        assertThat(config.agentKey()).isEqualTo("official-writing");
        assertThat(config.systemPrompt()).contains("公文写作");
        assertThat(config.defaultModelId()).isEqualTo(10L);
    }

    @Test
    void shouldRejectDisabledAgentForRuntime() {
        AgentConfigMapper mapper = mock(AgentConfigMapper.class);
        AgentServiceImpl service = new AgentServiceImpl(mapper);
        when(mapper.selectByAgentKey("official-writing")).thenReturn(agent(1L, false, 10L));

        assertThatThrownBy(() -> service.requireEnabledRuntimeConfig("official-writing"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent 不可用")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectMissingAgentForRuntime() {
        AgentConfigMapper mapper = mock(AgentConfigMapper.class);
        AgentServiceImpl service = new AgentServiceImpl(mapper);
        when(mapper.selectByAgentKey("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.requireEnabledRuntimeConfig("missing"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent 不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldCreateAgentWithDefaults() {
        AgentConfigMapper mapper = mock(AgentConfigMapper.class);
        AgentServiceImpl service = new AgentServiceImpl(mapper);
        ArgumentCaptor<AgentConfig> captor = ArgumentCaptor.forClass(AgentConfig.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(captor.capture())).thenAnswer(invocation -> {
            captor.getValue().setId(1L);
            return 1;
        });
        when(mapper.selectJoinedById(1L)).thenReturn(item(1L));

        AgentItem item = service.create(new CreateAgentCommand(
                "official-writing",
                "公文写作 Agent",
                "文",
                "通知、请示、汇报材料起草",
                "你是一个公文写作助手。",
                10L,
                null,
                null
        ));

        assertThat(item.agentId()).isEqualTo(1L);
        assertThat(captor.getValue().getEnabled()).isTrue();
        assertThat(captor.getValue().getSort()).isZero();
    }

    @Test
    void shouldRejectDuplicateAgentKey() {
        AgentConfigMapper mapper = mock(AgentConfigMapper.class);
        AgentServiceImpl service = new AgentServiceImpl(mapper);
        when(mapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(new CreateAgentCommand(
                "official-writing",
                "公文写作 Agent",
                "文",
                "通知、请示、汇报材料起草",
                "你是一个公文写作助手。",
                10L,
                true,
                1
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent 标识已存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldConvertDuplicateKeyExceptionWhenUpdateAgent() {
        AgentConfigMapper mapper = mock(AgentConfigMapper.class);
        AgentServiceImpl service = new AgentServiceImpl(mapper);
        when(mapper.selectById(1L)).thenReturn(agent(1L, true, 10L));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.updateById(any(AgentConfig.class))).thenThrow(new DuplicateKeyException("uk_agent_config_key"));

        assertThatThrownBy(() -> service.update(1L, new UpdateAgentCommand(
                "official-writing",
                "公文写作 Agent",
                "文",
                "通知、请示、汇报材料起草",
                "你是一个公文写作助手。",
                10L,
                true,
                1
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Agent 标识已存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldDeleteExistingAgent() {
        AgentConfigMapper mapper = mock(AgentConfigMapper.class);
        AgentServiceImpl service = new AgentServiceImpl(mapper);
        when(mapper.selectById(1L)).thenReturn(agent(1L, true, null));

        service.delete(1L);

        verify(mapper).deleteById(1L);
    }

    private AgentConfig agent(Long id, boolean enabled, Long defaultModelId) {
        AgentConfig agent = new AgentConfig();
        agent.setId(id);
        agent.setAgentKey("official-writing");
        agent.setName("公文写作 Agent");
        agent.setShortName("文");
        agent.setDescription("通知、请示、汇报材料起草");
        agent.setSystemPrompt("你是一个公文写作助手。");
        agent.setDefaultModelId(defaultModelId);
        agent.setEnabled(enabled);
        agent.setSort(1);
        return agent;
    }

    private AgentItem item(Long id) {
        return new AgentItem(
                id,
                "official-writing",
                "公文写作 Agent",
                "文",
                "通知、请示、汇报材料起草",
                "你是一个公文写作助手。",
                10L,
                "GPT-4o",
                true,
                1
        );
    }
}
