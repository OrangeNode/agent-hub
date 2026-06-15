package com.agenthub.skill.service.impl;

import com.agenthub.skill.entity.AgentSkillBinding;
import com.agenthub.skill.entity.SkillPackage;
import com.agenthub.skill.mapper.AgentSkillBindingMapper;
import com.agenthub.skill.mapper.SkillPackageMapper;
import com.agenthub.skill.service.SkillRuntimeContext;
import com.agenthub.skill.service.SkillRuntimeService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SkillRuntimeServiceImpl implements SkillRuntimeService {

    private static final int EXCERPT_MAX_LENGTH = 240;

    private final AgentSkillBindingMapper bindingMapper;
    private final SkillPackageMapper packageMapper;

    public SkillRuntimeServiceImpl(
            AgentSkillBindingMapper bindingMapper,
            SkillPackageMapper packageMapper
    ) {
        this.bindingMapper = bindingMapper;
        this.packageMapper = packageMapper;
    }

    @Override
    public SkillRuntimeContext buildRuntimeContext(Long agentId, List<Long> selectedSkillIds) {
        List<Long> skillIds = agentId == null ? normalizeIds(selectedSkillIds) : boundSkillIds(agentId);
        if (skillIds.isEmpty()) {
            return new SkillRuntimeContext("", List.of());
        }
        List<SkillPackage> skills = selectSkillsInOrder(skillIds);
        if (skills.isEmpty()) {
            return new SkillRuntimeContext("", List.of());
        }
        return new SkillRuntimeContext(buildPrompt(agentId == null, skills), skills.stream().map(SkillPackage::getId).toList());
    }

    @Override
    public String readSkillMarkdown(Long skillId, List<Long> allowedSkillIds) {
        if (skillId == null || !normalizeIds(allowedSkillIds).contains(skillId)) {
            return "当前会话未启用该 Skill";
        }
        SkillPackage skill = packageMapper.selectById(skillId);
        if (skill == null) {
            return "Skill 不存在";
        }
        return skill.getSkillMdContent();
    }

    private List<Long> boundSkillIds(Long agentId) {
        return bindingMapper.selectByAgentId(agentId).stream()
                .sorted(Comparator.comparing(binding -> binding.getSort() == null ? 0 : binding.getSort()))
                .map(AgentSkillBinding::getSkillId)
                .toList();
    }

    private List<SkillPackage> selectSkillsInOrder(List<Long> skillIds) {
        Map<Long, Integer> orderById = new LinkedHashMap<>();
        for (int index = 0; index < skillIds.size(); index++) {
            orderById.putIfAbsent(skillIds.get(index), index);
        }
        return packageMapper.selectByIds(new ArrayList<>(orderById.keySet())).stream()
                .filter(skill -> skill != null && orderById.containsKey(skill.getId()))
                .sorted(Comparator.comparingInt(skill -> orderById.get(skill.getId())))
                .toList();
    }

    private String buildPrompt(boolean selectedByUser, List<SkillPackage> skills) {
        StringBuilder builder = new StringBuilder();
        builder.append(selectedByUser ? "已选择以下 Skill。" : "已绑定以下 Skill。")
                .append("这里仅做渐进披露，不会完整注入 SKILL.md。")
                .append("如需完整说明，调用 skill.read，参数为对应的 skill_id。\n");
        for (SkillPackage skill : skills) {
            builder.append("\n[Skill]\n")
                    .append("Skill ID：").append(skill.getId()).append('\n')
                    .append("名称：").append(defaultString(skill.getName())).append('\n');
            if (!defaultString(skill.getDescription()).isBlank()) {
                builder.append("说明：").append(skill.getDescription()).append('\n');
            }
            builder.append("摘要：").append(excerpt(skill.getSkillMdContent())).append('\n');
            builder.append("完整说明：调用 skill.read，参数 skill_id=").append(skill.getId()).append('\n');
        }
        return builder.toString();
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> normalized = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && !normalized.contains(id)) {
                normalized.add(id);
            }
        }
        return normalized;
    }

    private String excerpt(String content) {
        String value = defaultString(content).replaceAll("\\s+", " ").trim();
        if (value.length() <= EXCERPT_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, EXCERPT_MAX_LENGTH) + "...（已截断，完整说明请调用 skill.read）";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
