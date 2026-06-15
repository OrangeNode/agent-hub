package com.agenthub.skill.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.skill.command.AgentSkillBindingCommand;
import com.agenthub.skill.dto.AgentSkillBindingItem;
import com.agenthub.skill.entity.AgentSkillBinding;
import com.agenthub.skill.entity.SkillPackage;
import com.agenthub.skill.mapper.AgentSkillBindingMapper;
import com.agenthub.skill.mapper.SkillPackageMapper;
import com.agenthub.skill.service.AgentSkillBindingService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSkillBindingServiceImpl implements AgentSkillBindingService {

    private final AgentSkillBindingMapper bindingMapper;
    private final SkillPackageMapper packageMapper;

    public AgentSkillBindingServiceImpl(
            AgentSkillBindingMapper bindingMapper,
            SkillPackageMapper packageMapper
    ) {
        this.bindingMapper = bindingMapper;
        this.packageMapper = packageMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentSkillBindingItem> listBindings(Long agentId) {
        return bindingMapper.selectByAgentId(agentId).stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    @Transactional
    public List<AgentSkillBindingItem> updateBindings(Long userId, Long agentId, List<AgentSkillBindingCommand> commands) {
        Map<Long, AgentSkillBindingCommand> uniqueCommands = new LinkedHashMap<>();
        if (commands != null) {
            for (AgentSkillBindingCommand command : commands) {
                if (command == null || command.skillId() == null || uniqueCommands.containsKey(command.skillId())) {
                    continue;
                }
                requireSkill(command.skillId());
                uniqueCommands.put(command.skillId(), command);
            }
        }
        bindingMapper.deleteByAgentId(agentId);
        OffsetDateTime now = OffsetDateTime.now();
        for (AgentSkillBindingCommand command : uniqueCommands.values()) {
            AgentSkillBinding binding = new AgentSkillBinding();
            binding.setAgentId(agentId);
            binding.setSkillId(command.skillId());
            binding.setSort(command.sort() == null ? 0 : command.sort());
            binding.setCreatedBy(userId);
            binding.setCreatedAt(now);
            binding.setUpdatedBy(userId);
            binding.setUpdatedAt(now);
            bindingMapper.insert(binding);
        }
        return listBindings(agentId);
    }

    private SkillPackage requireSkill(Long skillId) {
        SkillPackage skill = packageMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 不存在");
        }
        return skill;
    }

    private AgentSkillBindingItem toItem(AgentSkillBinding binding) {
        SkillPackage skill = packageMapper.selectById(binding.getSkillId());
        return new AgentSkillBindingItem(
                binding.getId(),
                binding.getAgentId(),
                binding.getSkillId(),
                skill == null ? null : skill.getSkillKey(),
                skill == null ? null : skill.getName(),
                binding.getSort()
        );
    }
}
