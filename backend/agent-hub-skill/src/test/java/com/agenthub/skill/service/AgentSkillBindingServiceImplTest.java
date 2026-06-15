package com.agenthub.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.skill.command.AgentSkillBindingCommand;
import com.agenthub.skill.entity.AgentSkillBinding;
import com.agenthub.skill.entity.SkillPackage;
import com.agenthub.skill.mapper.AgentSkillBindingMapper;
import com.agenthub.skill.mapper.SkillPackageMapper;
import com.agenthub.skill.service.impl.AgentSkillBindingServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentSkillBindingServiceImplTest {

    private final AgentSkillBindingMapper bindingMapper = mock(AgentSkillBindingMapper.class);
    private final SkillPackageMapper packageMapper = mock(SkillPackageMapper.class);
    private final AgentSkillBindingService service = new AgentSkillBindingServiceImpl(bindingMapper, packageMapper);

    @Test
    void shouldReplaceBindingsAndIgnoreDuplicateSkillIds() {
        when(packageMapper.selectById(10L)).thenReturn(skill(10L, "公文写作"));
        when(packageMapper.selectById(11L)).thenReturn(skill(11L, "数据分析"));

        service.updateBindings(7L, 1L, List.of(
                new AgentSkillBindingCommand(10L, 2),
                new AgentSkillBindingCommand(11L, 1),
                new AgentSkillBindingCommand(10L, 9)
        ));

        verify(bindingMapper).deleteByAgentId(1L);
        ArgumentCaptor<AgentSkillBinding> captor = ArgumentCaptor.forClass(AgentSkillBinding.class);
        verify(bindingMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(AgentSkillBinding::getSkillId)
                .containsExactly(10L, 11L);
        assertThat(captor.getAllValues()).extracting(AgentSkillBinding::getSort)
                .containsExactly(2, 1);
    }

    private SkillPackage skill(Long id, String name) {
        SkillPackage skill = new SkillPackage();
        skill.setId(id);
        skill.setName(name);
        skill.setSkillKey("skill-" + id);
        skill.setSkillMdContent("# " + name);
        return skill;
    }
}
