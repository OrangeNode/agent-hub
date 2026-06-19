package com.agenthub.skill.service.impl;

import com.agenthub.skill.entity.AgentSkillBinding;
import com.agenthub.skill.entity.SkillPackage;
import com.agenthub.skill.mapper.AgentSkillBindingMapper;
import com.agenthub.skill.mapper.SkillPackageMapper;
import com.agenthub.skill.service.SkillRuntimeContext;
import com.agenthub.skill.service.SkillRuntimePackage;
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
        List<SkillRuntimePackage> packages = skills.stream()
                .map(skill -> new SkillRuntimePackage(
                        skill.getId(),
                        skill.getSkillKey(),
                        skill.getName(),
                        skill.getBucketName(),
                        skill.getObjectKey(),
                        skill.getZipSha256()
                ))
                .toList();
        return new SkillRuntimeContext(
                buildPrompt(agentId == null, skills),
                skills.stream().map(SkillPackage::getId).toList(),
                packages
        );
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
            builder.append("sandbox 路径：/workspace/skills/")
                    .append(skill.getSkillKey())
                    .append("，可读取该目录下的 SKILL.md、references、scripts 和模板文件。\n");
            appendDocxWorkflow(builder, skill);
        }
        return builder.toString();
    }

    private void appendDocxWorkflow(StringBuilder builder, SkillPackage skill) {
        if (!"docx".equalsIgnoreCase(defaultString(skill.getSkillKey()))) {
            return;
        }
        builder.append("docx skill 工作流：必须先调用 skill.read 读取完整说明；")
                .append("C# 中间源码请用 sandbox.write_workspace_file 写入 /tmp/docx-work/Program.cs；")
                .append("运行 /workspace/skills/docx/scripts 下的脚本或 python-docx 时，")
                .append("最终 .docx 必须生成到 /workspace/artifacts；")
                .append("不要把 .cs/.py/.md 中间文件写入 /workspace/artifacts，")
                .append("只有最终 .docx 才是交付文件。\n");
        builder.append("docx fast path: for simple Chinese official notices/red-head documents without a supplied template, ")
                .append("prefer python-docx first. Write /workspace/work/docx_gen.py with sandbox.write_workspace_file, ")
                .append("execute `python3 /workspace/work/docx_gen.py` with timeout_seconds=120, and save the final .docx under ")
                .append("/workspace/artifacts. When setting CJK fonts, use run._element.get_or_add_rPr() plus ")
                .append("OxmlElement('w:rFonts'); do not access run.font.element.rPr. ")
                .append("If this fast path succeeds, do not attempt the C# build route.\n");
        builder.append("docx create override: for the C# creation route, write Program.cs directly to ")
                .append("/tmp/docx-work/Program.cs with sandbox.write_workspace_file. ")
                .append("Do not use /workspace/work/docx/Program.cs for docx Program.cs. ")
                .append("Build with sandbox.exec command `bash /workspace/skills/docx/scripts/docx build ")
                .append("/workspace/artifacts/南宁市政府端午放假通知.docx` and working_dir `/workspace`. ")
                .append("For docx init/build, always set timeout_seconds to 300; never use 120 seconds. ")
                .append("Do not loop on init/build probes after a timeout; immediately fall back to python-docx once with timeout_seconds=120. ")
                .append("Do not use `cd /workspace/skills/docx && ./scripts/docx ...`.\n");
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
