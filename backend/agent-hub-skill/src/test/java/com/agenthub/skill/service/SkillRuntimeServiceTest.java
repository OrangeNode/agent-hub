package com.agenthub.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.skill.entity.AgentSkillBinding;
import com.agenthub.skill.entity.SkillPackage;
import com.agenthub.skill.mapper.AgentSkillBindingMapper;
import com.agenthub.skill.mapper.SkillPackageMapper;
import com.agenthub.skill.service.impl.SkillRuntimeServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillRuntimeServiceTest {

    private final AgentSkillBindingMapper bindingMapper = mock(AgentSkillBindingMapper.class);
    private final SkillPackageMapper packageMapper = mock(SkillPackageMapper.class);
    private final SkillRuntimeService service = new SkillRuntimeServiceImpl(bindingMapper, packageMapper);

    @Test
    void shouldBuildProgressivePromptForSelectedSkillsWhenAgentIsDefault() {
        SkillPackage skill = skill(10L, "公文写作", "写通知", "# 公文写作\n" + "完整规则".repeat(200));
        when(packageMapper.selectByIds(List.of(10L))).thenReturn(List.of(skill));

        SkillRuntimeContext context = service.buildRuntimeContext(null, List.of(10L));

        assertThat(context.skillIds()).containsExactly(10L);
        assertThat(context.prompt())
                .contains("已选择以下 Skill")
                .contains("Skill ID：10")
                .contains("公文写作")
                .contains("如需完整说明，调用 skill.read");
        assertThat(context.prompt()).doesNotContain("完整规则".repeat(100));
    }

    @Test
    void shouldIgnoreSelectedSkillsAndUseBoundSkillsWhenAgentIsBusinessAgent() {
        AgentSkillBinding binding = new AgentSkillBinding();
        binding.setAgentId(1L);
        binding.setSkillId(11L);
        binding.setSort(1);
        SkillPackage boundSkill = skill(11L, "数据分析", "分析指标", "# 数据分析\n先确认口径");
        when(bindingMapper.selectByAgentId(1L)).thenReturn(List.of(binding));
        when(packageMapper.selectByIds(List.of(11L))).thenReturn(List.of(boundSkill));

        SkillRuntimeContext context = service.buildRuntimeContext(1L, List.of(10L));

        assertThat(context.skillIds()).containsExactly(11L);
        assertThat(context.prompt()).contains("已绑定以下 Skill").contains("数据分析");
        assertThat(context.prompt()).doesNotContain("10");
    }

    @Test
    void shouldBuildRuntimePackagesAndExposeSandboxPathForSelectedSkills() {
        SkillPackage skill = skill(6L, "docx", "DOCX 文档", "生成 docx", "# DOCX\n完整规则");
        when(packageMapper.selectByIds(List.of(6L))).thenReturn(List.of(skill));

        SkillRuntimeContext context = service.buildRuntimeContext(null, List.of(6L));

        assertThat(context.packages())
                .singleElement()
                .satisfies(runtimePackage -> {
                    assertThat(runtimePackage.skillId()).isEqualTo(6L);
                    assertThat(runtimePackage.skillKey()).isEqualTo("docx");
                    assertThat(runtimePackage.objectKey()).isEqualTo("skill-packages/docx.zip");
                    assertThat(runtimePackage.zipSha256()).hasSize(64);
                });
        assertThat(context.prompt()).contains("/workspace/skills/docx");
        assertThat(context.prompt())
                .contains("sandbox.write_workspace_file")
                .contains("docx fast path")
                .contains("/workspace/work/docx_gen.py")
                .contains("timeout_seconds=120")
                .contains("/tmp/docx-work/Program.cs")
                .contains("bash /workspace/skills/docx/scripts/docx build")
                .contains("timeout_seconds to 300")
                .contains("/workspace/artifacts")
                .contains(".docx");
    }

    @Test
    void shouldReadFullSkillMdOnlyWhenSkillIsAllowedInCurrentContext() {
        SkillPackage skill = skill(10L, "公文写作", "写通知", "# 公文写作\n完整内容");
        when(packageMapper.selectById(10L)).thenReturn(skill);

        assertThat(service.readSkillMarkdown(10L, List.of(10L))).isEqualTo("# 公文写作\n完整内容");
        assertThat(service.readSkillMarkdown(10L, List.of(11L))).isEqualTo("当前会话未启用该 Skill");
    }

    private SkillPackage skill(Long id, String name, String description, String skillMdContent) {
        return skill(id, "skill-" + id, name, description, skillMdContent);
    }

    private SkillPackage skill(Long id, String skillKey, String name, String description, String skillMdContent) {
        SkillPackage skill = new SkillPackage();
        skill.setId(id);
        skill.setSkillKey(skillKey);
        skill.setName(name);
        skill.setDescription(description);
        skill.setSkillMdContent(skillMdContent);
        skill.setBucketName("agent-hub");
        skill.setObjectKey("skill-packages/" + skillKey + ".zip");
        skill.setZipSha256("a".repeat(64));
        skill.setFileCount(1);
        return skill;
    }
}
