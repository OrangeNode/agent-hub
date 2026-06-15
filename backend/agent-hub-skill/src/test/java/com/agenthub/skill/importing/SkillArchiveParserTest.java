package com.agenthub.skill.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SkillArchiveParserTest {

    private final SkillArchiveParser parser = new SkillArchiveParser();

    @Test
    void shouldReadNameFromSkillMdFrontmatter() {
        byte[] zipBytes = zip(Map.of(
                "SKILL.md", """
                        ---
                        name: 公文写作
                        description: 生成正式中文公文
                        ---

                        # 不应优先使用这个标题
                        """,
                "examples/input.txt", "请写通知"
        ));

        ParsedSkillArchive archive = parser.parse(zipBytes, "official-writing.zip");

        assertThat(archive.name()).isEqualTo("公文写作");
        assertThat(archive.description()).isEqualTo("生成正式中文公文");
        assertThat(archive.skillMdPath()).isEqualTo("SKILL.md");
        assertThat(archive.skillMdContent()).contains("不应优先使用这个标题");
        assertThat(archive.files()).extracting(ParsedSkillFile::relativePath)
                .containsExactly("SKILL.md", "examples/input.txt");
    }

    @Test
    void shouldFallbackToFirstHeadingWhenFrontmatterHasNoName() {
        byte[] zipBytes = zip(Map.of(
                "SKILL.md", """
                        ---
                        description: 使用标题作为名称
                        ---

                        # 数据分析助手
                        """
        ));

        ParsedSkillArchive archive = parser.parse(zipBytes, "analysis.zip");

        assertThat(archive.name()).isEqualTo("数据分析助手");
        assertThat(archive.description()).isEqualTo("使用标题作为名称");
    }

    @Test
    void shouldAcceptNestedSkillMdAndStripSkillRootFromManifest() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("writing-skill/SKILL.md", "# 嵌套 Skill");
        files.put("writing-skill/assets/template.md", "模板");
        files.put("writing-skill/scripts/run.py", "print('ok')");

        ParsedSkillArchive archive = parser.parse(zip(files), "nested.zip");

        assertThat(archive.name()).isEqualTo("嵌套 Skill");
        assertThat(archive.rootDir()).isEqualTo("writing-skill");
        assertThat(archive.skillMdPath()).isEqualTo("SKILL.md");
        assertThat(archive.files()).extracting(ParsedSkillFile::relativePath)
                .containsExactly("SKILL.md", "assets/template.md", "scripts/run.py");
    }

    @Test
    void shouldFallbackToFilenameWhenNoNameOrHeadingExists() {
        byte[] zipBytes = zip(Map.of("SKILL.md", "按用户输入生成摘要。"));

        ParsedSkillArchive archive = parser.parse(zipBytes, "summary-helper.zip");

        assertThat(archive.name()).isEqualTo("summary-helper");
    }

    @Test
    void shouldRejectPackageWithoutSkillMd() {
        byte[] zipBytes = zip(Map.of("README.md", "说明"));

        assertThatThrownBy(() -> parser.parse(zipBytes, "broken.zip"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Skill 包缺少 SKILL.md")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private byte[] zip(Map<String, String> files) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : files.entrySet()) {
                    zipOutputStream.putNextEntry(new ZipEntry(entry.getKey()));
                    zipOutputStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zipOutputStream.closeEntry();
                }
            }
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
