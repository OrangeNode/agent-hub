package com.agenthub.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.skill.command.CreateSkillUploadCommand;
import com.agenthub.skill.command.SkillImportApplyCommand;
import com.agenthub.skill.dto.SkillFileContent;
import com.agenthub.skill.dto.SkillImportPreview;
import com.agenthub.skill.dto.SkillPackageItem;
import com.agenthub.skill.entity.SkillFileManifest;
import com.agenthub.skill.entity.SkillImport;
import com.agenthub.skill.entity.SkillPackage;
import com.agenthub.skill.mapper.AgentSkillBindingMapper;
import com.agenthub.skill.mapper.SkillFileManifestMapper;
import com.agenthub.skill.mapper.SkillImportMapper;
import com.agenthub.skill.mapper.SkillPackageMapper;
import com.agenthub.skill.service.impl.SkillPackageServiceImpl;
import com.agenthub.storage.config.StorageProperties;
import com.agenthub.storage.object.ObjectMetadata;
import com.agenthub.storage.object.ObjectStorageClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SkillPackageServiceImplTest {

    private final SkillPackageMapper packageMapper = mock(SkillPackageMapper.class);
    private final SkillImportMapper importMapper = mock(SkillImportMapper.class);
    private final SkillFileManifestMapper fileMapper = mock(SkillFileManifestMapper.class);
    private final AgentSkillBindingMapper bindingMapper = mock(AgentSkillBindingMapper.class);
    private final ObjectStorageClient objectStorageClient = mock(ObjectStorageClient.class);
    private final SkillPackageService service = new SkillPackageServiceImpl(
            packageMapper,
            importMapper,
            fileMapper,
            bindingMapper,
            objectStorageClient,
            storageProperties(),
            new ObjectMapper()
    );

    @Test
    void shouldCreateSkillWhenConfirmedImportHasNoDuplicateName() {
        when(objectStorageClient.presignPut(anyString(), anyString(), any()))
                .thenReturn("https://upload.example.com/skill.zip");
        when(importMapper.insert(any(SkillImport.class))).thenAnswer(invocation -> {
            SkillImport skillImport = invocation.getArgument(0);
            skillImport.setId(1L);
            return 1;
        });
        when(importMapper.selectById(1L)).thenReturn(importRecord(1L, "skill-packages/demo.zip"));
        when(objectStorageClient.stat("skill-packages/demo.zip"))
                .thenReturn(new ObjectMetadata(100, "application/zip"));
        when(objectStorageClient.readBytes("skill-packages/demo.zip")).thenReturn(zip(Map.of(
                "SKILL.md", """
                        ---
                        name: 公文写作
                        description: 写通知和请示
                        ---

                        # 标题
                        """,
                "scripts/render_docx.py", "print('python renderer')"
        )));
        when(packageMapper.selectByName("公文写作")).thenReturn(null);
        when(packageMapper.insert(any(SkillPackage.class))).thenAnswer(invocation -> {
            SkillPackage skill = invocation.getArgument(0);
            skill.setId(10L);
            return 1;
        });

        service.createUploadUrl(7L, new CreateSkillUploadCommand("demo.zip", "application/zip", 100));
        SkillImportPreview preview = service.confirmImport(7L, 1L);

        assertThat(preview.duplicate()).isFalse();
        assertThat(preview.imported()).isTrue();
        assertThat(preview.importedSkill().skillId()).isEqualTo(10L);
        assertThat(preview.importedSkill().name()).isEqualTo("公文写作");
        verify(objectStorageClient).putBytes(
                eq("agent-hub"),
                eq("skill-packages/extracted/1/SKILL.md"),
                eq("text/markdown"),
                any(byte[].class)
        );
        verify(objectStorageClient).putBytes(
                eq("agent-hub"),
                eq("skill-packages/extracted/1/scripts/render_docx.py"),
                eq("text/x-python"),
                any(byte[].class)
        );
        ArgumentCaptor<SkillFileManifest> fileCaptor = ArgumentCaptor.forClass(SkillFileManifest.class);
        verify(fileMapper, org.mockito.Mockito.times(2)).insert(fileCaptor.capture());
        assertThat(fileCaptor.getAllValues())
                .extracting(SkillFileManifest::getObjectKey)
                .containsExactly(
                        "skill-packages/extracted/1/SKILL.md",
                        "skill-packages/extracted/1/scripts/render_docx.py"
                );
        assertThat(fileCaptor.getAllValues())
                .extracting(SkillFileManifest::getContentType)
                .containsExactly("text/markdown", "text/x-python");
    }

    @Test
    void shouldReturnDuplicatePreviewWithoutOverwritingExistingSkill() {
        when(importMapper.selectById(1L)).thenReturn(importRecord(1L, "skill-packages/demo.zip"));
        when(objectStorageClient.stat("skill-packages/demo.zip"))
                .thenReturn(new ObjectMetadata(100, "application/zip"));
        when(objectStorageClient.readBytes("skill-packages/demo.zip")).thenReturn(zip(Map.of(
                "SKILL.md", "---\nname: 公文写作\n---\n内容"
        )));
        SkillPackage existing = skill(8L, "公文写作", "old-key");
        when(packageMapper.selectByName("公文写作")).thenReturn(existing);

        SkillImportPreview preview = service.confirmImport(7L, 1L);

        assertThat(preview.duplicate()).isTrue();
        assertThat(preview.imported()).isFalse();
        assertThat(preview.duplicateSkillId()).isEqualTo(8L);
        assertThat(preview.parsedName()).isEqualTo("公文写作");
        verify(packageMapper, org.mockito.Mockito.never()).insert(any(SkillPackage.class));
    }

    @Test
    void shouldOverwriteExistingSkillWhenApplyingDuplicateImport() throws Exception {
        SkillImport skillImport = parsedImport(1L, "公文写作");
        when(importMapper.selectById(1L)).thenReturn(skillImport);
        SkillPackage existing = skill(8L, "公文写作", "gong-wen-xie-zuo");
        when(packageMapper.selectByName("公文写作")).thenReturn(existing);

        SkillPackageItem result = service.applyImport(7L, 1L, new SkillImportApplyCommand("OVERWRITE", null));

        assertThat(result.skillId()).isEqualTo(8L);
        assertThat(result.name()).isEqualTo("公文写作");
        ArgumentCaptor<SkillPackage> captor = ArgumentCaptor.forClass(SkillPackage.class);
        verify(packageMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(8L);
        assertThat(captor.getValue().getSkillMdContent()).contains("覆盖后的内容");
        verify(fileMapper).deleteBySkillId(8L);
        verify(fileMapper).insert(any(SkillFileManifest.class));
    }

    @Test
    void shouldCreateRenamedSkillWhenApplyingDuplicateImportWithNewName() throws Exception {
        SkillImport skillImport = parsedImport(1L, "公文写作");
        when(importMapper.selectById(1L)).thenReturn(skillImport);
        when(packageMapper.selectByName("重命名 Skill")).thenReturn(null);
        when(packageMapper.insert(any(SkillPackage.class))).thenAnswer(invocation -> {
            SkillPackage skill = invocation.getArgument(0);
            skill.setId(9L);
            return 1;
        });

        SkillPackageItem result = service.applyImport(7L, 1L, new SkillImportApplyCommand("RENAME", "重命名 Skill"));

        assertThat(result.skillId()).isEqualTo(9L);
        assertThat(result.name()).isEqualTo("重命名 Skill");
        ArgumentCaptor<SkillPackage> captor = ArgumentCaptor.forClass(SkillPackage.class);
        verify(packageMapper).insert(captor.capture());
        assertThat(captor.getValue().getSkillKey()).isEqualTo("skill");
    }

    @Test
    void shouldRejectRenameWhenNewNameAlreadyExists() throws Exception {
        when(importMapper.selectById(1L)).thenReturn(parsedImport(1L, "公文写作"));
        when(packageMapper.selectByName("重命名 Skill")).thenReturn(skill(9L, "重命名 Skill", "renamed"));

        assertThatThrownBy(() -> service.applyImport(7L, 1L,
                new SkillImportApplyCommand("RENAME", "重命名 Skill")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Skill 名称已存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldDeleteSkillFilesAndBindings() {
        when(packageMapper.selectById(5L)).thenReturn(skill(5L, "待删除", "delete-me"));

        service.delete(5L);

        verify(bindingMapper).deleteBySkillId(5L);
        verify(fileMapper).deleteBySkillId(5L);
        verify(packageMapper).deleteById(5L);
    }

    @Test
    void shouldPreviewTextFileFromExtractedObjectWhenManifestHasObjectKey() {
        SkillPackage skill = skill(5L, "Preview Skill", "preview-skill");
        skill.setBucketName("agent-hub");
        skill.setObjectKey("skill-packages/demo.zip");
        skill.setOriginalFilename("demo.zip");
        SkillFileManifest file = file(11L, 5L, "assets/template.md", false, 14L);
        file.setBucketName("agent-hub");
        file.setObjectKey("skill-packages/extracted/1/assets/template.md");
        file.setContentType("text/markdown");
        when(packageMapper.selectById(5L)).thenReturn(skill);
        when(fileMapper.selectBySkillId(5L)).thenReturn(List.of(file));
        when(objectStorageClient.readBytes("agent-hub", "skill-packages/extracted/1/assets/template.md"))
                .thenReturn("template content".getBytes(StandardCharsets.UTF_8));
        when(objectStorageClient.readBytes("agent-hub", "skill-packages/demo.zip")).thenReturn(zip(Map.of(
                "demo-skill/SKILL.md", "# Preview Skill",
                "demo-skill/assets/template.md", "zip content"
        )));

        SkillFileContent content = service.fileContent(5L, "assets/template.md");

        assertThat(content.relativePath()).isEqualTo("assets/template.md");
        assertThat(content.previewable()).isTrue();
        assertThat(content.content()).isEqualTo("template content");
        verify(objectStorageClient).readBytes("agent-hub", "skill-packages/extracted/1/assets/template.md");
        verify(objectStorageClient, never()).readBytes("agent-hub", "skill-packages/demo.zip");
    }

    @Test
    void shouldPreviewTextFileFromNestedSkillArchive() {
        SkillPackage skill = skill(5L, "Preview Skill", "preview-skill");
        skill.setBucketName("agent-hub");
        skill.setObjectKey("skill-packages/demo.zip");
        skill.setOriginalFilename("demo.zip");
        SkillFileManifest file = file(11L, 5L, "assets/template.md", false, 14L);
        when(packageMapper.selectById(5L)).thenReturn(skill);
        when(fileMapper.selectBySkillId(5L)).thenReturn(List.of(file));
        when(objectStorageClient.readBytes("agent-hub", "skill-packages/demo.zip")).thenReturn(zip(Map.of(
                "demo-skill/SKILL.md", "# Preview Skill",
                "demo-skill/assets/template.md", "template content"
        )));

        SkillFileContent content = service.fileContent(5L, "assets/template.md");

        assertThat(content.relativePath()).isEqualTo("assets/template.md");
        assertThat(content.previewable()).isTrue();
        assertThat(content.content()).isEqualTo("template content");
        assertThat(content.message()).isNull();
    }

    @Test
    void shouldRejectPreviewWhenFilePathIsNotInManifest() {
        when(packageMapper.selectById(5L)).thenReturn(skill(5L, "Preview Skill", "preview-skill"));
        when(fileMapper.selectBySkillId(5L)).thenReturn(List.of(file(11L, 5L, "SKILL.md", false, 12L)));

        assertThatThrownBy(() -> service.fileContent(5L, "missing.md"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Skill 文件不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectPreviewWhenPathIsDirectory() {
        when(packageMapper.selectById(5L)).thenReturn(skill(5L, "Preview Skill", "preview-skill"));
        when(fileMapper.selectBySkillId(5L)).thenReturn(List.of(file(12L, 5L, "assets", true, 0L)));

        assertThatThrownBy(() -> service.fileContent(5L, "assets"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("目录不能预览")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private SkillImport importRecord(Long importId, String objectKey) {
        SkillImport skillImport = new SkillImport();
        skillImport.setId(importId);
        skillImport.setCreatedBy(7L);
        skillImport.setOriginalFilename("demo.zip");
        skillImport.setBucketName("agent-hub");
        skillImport.setObjectKey(objectKey);
        skillImport.setContentType("application/zip");
        skillImport.setFileSize(100L);
        skillImport.setStatus("UPLOADED");
        return skillImport;
    }

    private SkillImport parsedImport(Long importId, String name) throws Exception {
        SkillImport skillImport = importRecord(importId, "skill-packages/demo.zip");
        skillImport.setStatus("PARSED");
        skillImport.setParsedName(name);
        skillImport.setParsedDescription("说明");
        skillImport.setSkillMdPath("SKILL.md");
        skillImport.setSkillMdContent("# 公文写作\n覆盖后的内容");
        skillImport.setFileCount(1);
        skillImport.setManifestJson("""
                [{"relativePath":"SKILL.md","directory":false,"sizeBytes":32,"sha256":"abc"}]
                """);
        return skillImport;
    }

    private SkillPackage skill(Long id, String name, String key) {
        SkillPackage skill = new SkillPackage();
        skill.setId(id);
        skill.setName(name);
        skill.setSkillKey(key);
        skill.setDescription("旧说明");
        skill.setSkillMdContent("旧内容");
        skill.setFileCount(1);
        return skill;
    }

    private SkillFileManifest file(Long id, Long skillId, String path, boolean directory, long sizeBytes) {
        SkillFileManifest file = new SkillFileManifest();
        file.setId(id);
        file.setSkillId(skillId);
        file.setRelativePath(path);
        file.setDirectory(directory);
        file.setSizeBytes(sizeBytes);
        return file;
    }

    private StorageProperties storageProperties() {
        StorageProperties properties = new StorageProperties();
        properties.setBucket("agent-hub");
        properties.setPresignedUploadTtlMinutes(10);
        return properties;
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
