package com.agenthub.skill.service.impl;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.skill.command.CreateSkillUploadCommand;
import com.agenthub.skill.command.SkillImportApplyCommand;
import com.agenthub.skill.dto.SkillFileContent;
import com.agenthub.skill.dto.SkillFileItem;
import com.agenthub.skill.dto.SkillImportPreview;
import com.agenthub.skill.dto.SkillOptionItem;
import com.agenthub.skill.dto.SkillPackageItem;
import com.agenthub.skill.dto.SkillUploadUrl;
import com.agenthub.skill.entity.SkillFileManifest;
import com.agenthub.skill.entity.SkillImport;
import com.agenthub.skill.entity.SkillPackage;
import com.agenthub.skill.importing.ParsedSkillArchive;
import com.agenthub.skill.importing.ParsedSkillFile;
import com.agenthub.skill.importing.SkillArchiveParser;
import com.agenthub.skill.mapper.AgentSkillBindingMapper;
import com.agenthub.skill.mapper.SkillFileManifestMapper;
import com.agenthub.skill.mapper.SkillImportMapper;
import com.agenthub.skill.mapper.SkillPackageMapper;
import com.agenthub.skill.service.SkillPackageService;
import com.agenthub.storage.config.StorageProperties;
import com.agenthub.storage.object.ObjectMetadata;
import com.agenthub.storage.object.ObjectStorageClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillPackageServiceImpl implements SkillPackageService {

    private static final String STATUS_UPLOADED = "UPLOADED";
    private static final String STATUS_PARSED = "PARSED";
    private static final String STATUS_APPLIED = "APPLIED";
    private static final String MODE_OVERWRITE = "OVERWRITE";
    private static final String MODE_RENAME = "RENAME";
    private static final String SKILL_MD = "SKILL.md";
    private static final long MAX_PREVIEW_BYTES = 512 * 1024;
    private static final TypeReference<List<ParsedSkillFile>> FILE_LIST_TYPE = new TypeReference<>() {
    };

    private final SkillPackageMapper packageMapper;
    private final SkillImportMapper importMapper;
    private final SkillFileManifestMapper fileMapper;
    private final AgentSkillBindingMapper bindingMapper;
    private final ObjectStorageClient objectStorageClient;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final SkillArchiveParser archiveParser;

    @Autowired
    public SkillPackageServiceImpl(
            SkillPackageMapper packageMapper,
            SkillImportMapper importMapper,
            SkillFileManifestMapper fileMapper,
            AgentSkillBindingMapper bindingMapper,
            ObjectStorageClient objectStorageClient,
            StorageProperties storageProperties
    ) {
        this(packageMapper, importMapper, fileMapper, bindingMapper, objectStorageClient, storageProperties,
                new ObjectMapper());
    }

    public SkillPackageServiceImpl(
            SkillPackageMapper packageMapper,
            SkillImportMapper importMapper,
            SkillFileManifestMapper fileMapper,
            AgentSkillBindingMapper bindingMapper,
            ObjectStorageClient objectStorageClient,
            StorageProperties storageProperties,
            ObjectMapper objectMapper
    ) {
        this.packageMapper = packageMapper;
        this.importMapper = importMapper;
        this.fileMapper = fileMapper;
        this.bindingMapper = bindingMapper;
        this.objectStorageClient = objectStorageClient;
        this.storageProperties = storageProperties;
        this.objectMapper = objectMapper;
        this.archiveParser = new SkillArchiveParser();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkillPackageItem> list() {
        return packageMapper.selectOrderedList().stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkillOptionItem> listAvailable() {
        return packageMapper.selectOrderedList().stream()
                .map(skill -> new SkillOptionItem(
                        skill.getId(),
                        skill.getSkillKey(),
                        skill.getName(),
                        skill.getDescription()
                ))
                .toList();
    }

    @Override
    @Transactional
    public SkillUploadUrl createUploadUrl(Long userId, CreateSkillUploadCommand command) {
        String filename = requireZipFilename(command.filename());
        String contentType = defaultContentType(command.contentType());
        if (command.fileSize() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包文件大小不正确");
        }
        OffsetDateTime now = OffsetDateTime.now();
        String objectKey = buildObjectKey(filename, now);
        SkillImport skillImport = new SkillImport();
        skillImport.setOriginalFilename(filename);
        skillImport.setBucketName(storageProperties.getBucket());
        skillImport.setObjectKey(objectKey);
        skillImport.setContentType(contentType);
        skillImport.setFileSize(command.fileSize());
        skillImport.setStatus(STATUS_UPLOADED);
        skillImport.setCreatedBy(userId);
        skillImport.setCreatedAt(now);
        skillImport.setUpdatedAt(now);
        importMapper.insert(skillImport);

        Duration ttl = Duration.ofMinutes(storageProperties.getPresignedUploadTtlMinutes());
        String uploadUrl = objectStorageClient.presignPut(objectKey, contentType, ttl);
        return new SkillUploadUrl(
                skillImport.getId(),
                uploadUrl,
                "PUT",
                Map.of("Content-Type", contentType),
                now.plus(ttl)
        );
    }

    @Override
    @Transactional
    public SkillImportPreview confirmImport(Long userId, Long importId) {
        SkillImport skillImport = requireImport(importId, userId);
        ObjectMetadata metadata = objectStorageClient.stat(skillImport.getObjectKey());
        if (metadata.size() != skillImport.getFileSize()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包上传失败，请重新上传");
        }
        ParsedSkillArchive archive = archiveParser.parse(
                objectStorageClient.readBytes(skillImport.getObjectKey()),
                skillImport.getOriginalFilename()
        );
        fillParsedImport(skillImport, archive);
        importMapper.updateById(skillImport);

        SkillPackage duplicateSkill = packageMapper.selectByName(archive.name());
        if (duplicateSkill != null) {
            return new SkillImportPreview(
                    skillImport.getId(),
                    true,
                    false,
                    duplicateSkill.getId(),
                    archive.name(),
                    archive.description(),
                    archive.files().size(),
                    archive.skillMdContent(),
                    null
            );
        }
        SkillPackageItem importedSkill = createSkill(userId, skillImport, archive.name(), archive.description());
        markApplied(skillImport);
        return new SkillImportPreview(
                skillImport.getId(),
                false,
                true,
                null,
                archive.name(),
                archive.description(),
                archive.files().size(),
                archive.skillMdContent(),
                importedSkill
        );
    }

    @Override
    @Transactional
    public SkillPackageItem applyImport(Long userId, Long importId, SkillImportApplyCommand command) {
        SkillImport skillImport = requireParsedImport(importId, userId);
        String mode = command.mode() == null ? "" : command.mode().trim().toUpperCase(Locale.ROOT);
        SkillPackageItem result;
        if (MODE_OVERWRITE.equals(mode)) {
            SkillPackage existed = packageMapper.selectByName(skillImport.getParsedName());
            if (existed == null) {
                result = createSkill(userId, skillImport, skillImport.getParsedName(), skillImport.getParsedDescription());
            } else {
                result = overwriteSkill(userId, existed, skillImport);
            }
        } else if (MODE_RENAME.equals(mode)) {
            String newName = requireName(command.name());
            if (packageMapper.selectByName(newName) != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 名称已存在");
            }
            result = createSkill(userId, skillImport, newName, skillImport.getParsedDescription());
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "导入处理方式不正确");
        }
        markApplied(skillImport);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SkillFileItem> files(Long skillId) {
        requireSkill(skillId);
        return fileMapper.selectBySkillId(skillId).stream()
                .map(file -> new SkillFileItem(
                        file.getId(),
                        file.getSkillId(),
                        file.getRelativePath(),
                        Boolean.TRUE.equals(file.getDirectory()),
                        file.getSizeBytes() == null ? 0 : file.getSizeBytes(),
                        file.getSha256()
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SkillFileContent fileContent(Long skillId, String relativePath) {
        SkillPackage skill = requireSkill(skillId);
        String normalizedPath = normalizePreviewPath(relativePath);
        SkillFileManifest manifest = fileMapper.selectBySkillId(skillId).stream()
                .filter(file -> normalizedPath.equals(file.getRelativePath()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Skill 文件不存在"));
        if (Boolean.TRUE.equals(manifest.getDirectory())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "目录不能预览");
        }
        long sizeBytes = manifest.getSizeBytes() == null ? 0 : manifest.getSizeBytes();
        if (sizeBytes > MAX_PREVIEW_BYTES) {
            return new SkillFileContent(
                    manifest.getId(),
                    manifest.getSkillId(),
                    manifest.getRelativePath(),
                    sizeBytes,
                    false,
                    null,
                    "文件超过 512 KB，暂不支持在线预览"
            );
        }

        byte[] archiveBytes = objectStorageClient.readBytes(skill.getBucketName(), skill.getObjectKey());
        byte[] fileBytes = readArchiveFile(archiveBytes, normalizedPath);
        if (isBinary(fileBytes)) {
            return new SkillFileContent(
                    manifest.getId(),
                    manifest.getSkillId(),
                    manifest.getRelativePath(),
                    fileBytes.length,
                    false,
                    null,
                    "该文件不是文本文件，暂不支持预览"
            );
        }
        return new SkillFileContent(
                manifest.getId(),
                manifest.getSkillId(),
                manifest.getRelativePath(),
                fileBytes.length,
                true,
                new String(fileBytes, StandardCharsets.UTF_8),
                null
        );
    }

    @Override
    @Transactional
    public void delete(Long skillId) {
        requireSkill(skillId);
        bindingMapper.deleteBySkillId(skillId);
        fileMapper.deleteBySkillId(skillId);
        packageMapper.deleteById(skillId);
    }

    private SkillPackageItem createSkill(Long userId, SkillImport skillImport, String name, String description) {
        OffsetDateTime now = OffsetDateTime.now();
        SkillPackage skill = new SkillPackage();
        skill.setSkillKey(uniqueSkillKey(name));
        skill.setName(name);
        skill.setDescription(description);
        fillPackageFromImport(skill, skillImport);
        skill.setCreatedBy(userId);
        skill.setCreatedAt(now);
        skill.setUpdatedBy(userId);
        skill.setUpdatedAt(now);
        packageMapper.insert(skill);
        replaceFiles(skill.getId(), skillImport);
        return toItem(skill);
    }

    private SkillPackageItem overwriteSkill(Long userId, SkillPackage existed, SkillImport skillImport) {
        SkillPackage updated = new SkillPackage();
        updated.setId(existed.getId());
        updated.setSkillKey(existed.getSkillKey());
        updated.setName(existed.getName());
        updated.setDescription(skillImport.getParsedDescription());
        fillPackageFromImport(updated, skillImport);
        updated.setUpdatedBy(userId);
        updated.setUpdatedAt(OffsetDateTime.now());
        packageMapper.updateById(updated);
        fileMapper.deleteBySkillId(existed.getId());
        replaceFiles(existed.getId(), skillImport);
        return toItem(updated);
    }

    private void fillPackageFromImport(SkillPackage skill, SkillImport skillImport) {
        skill.setOriginalFilename(skillImport.getOriginalFilename());
        skill.setBucketName(skillImport.getBucketName());
        skill.setObjectKey(skillImport.getObjectKey());
        skill.setContentType(skillImport.getContentType());
        skill.setFileSize(skillImport.getFileSize());
        skill.setSkillMdPath(skillImport.getSkillMdPath());
        skill.setSkillMdContent(skillImport.getSkillMdContent());
        skill.setFileCount(skillImport.getFileCount());
    }

    private void replaceFiles(Long skillId, SkillImport skillImport) {
        for (ParsedSkillFile parsedFile : readManifest(skillImport.getManifestJson())) {
            SkillFileManifest file = new SkillFileManifest();
            file.setSkillId(skillId);
            file.setRelativePath(parsedFile.relativePath());
            file.setDirectory(parsedFile.directory());
            file.setSizeBytes(parsedFile.sizeBytes());
            file.setSha256(parsedFile.sha256());
            file.setCreatedAt(OffsetDateTime.now());
            fileMapper.insert(file);
        }
    }

    private void fillParsedImport(SkillImport skillImport, ParsedSkillArchive archive) {
        skillImport.setStatus(STATUS_PARSED);
        skillImport.setParsedName(archive.name());
        skillImport.setParsedDescription(archive.description());
        skillImport.setRootDir(archive.rootDir());
        skillImport.setSkillMdPath(archive.skillMdPath());
        skillImport.setSkillMdContent(archive.skillMdContent());
        skillImport.setFileCount(archive.files().size());
        skillImport.setManifestJson(writeManifest(archive.files()));
        skillImport.setErrorMessage(null);
        skillImport.setUpdatedAt(OffsetDateTime.now());
    }

    private void markApplied(SkillImport skillImport) {
        SkillImport updated = new SkillImport();
        updated.setId(skillImport.getId());
        updated.setStatus(STATUS_APPLIED);
        updated.setUpdatedAt(OffsetDateTime.now());
        importMapper.updateById(updated);
    }

    private SkillImport requireImport(Long importId, Long userId) {
        SkillImport skillImport = importMapper.selectById(importId);
        if (skillImport == null || !userId.equals(skillImport.getCreatedBy())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 导入记录不存在");
        }
        if (STATUS_APPLIED.equals(skillImport.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 导入记录已处理");
        }
        return skillImport;
    }

    private SkillImport requireParsedImport(Long importId, Long userId) {
        SkillImport skillImport = requireImport(importId, userId);
        if (!STATUS_PARSED.equals(skillImport.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 导入记录尚未解析");
        }
        return skillImport;
    }

    private SkillPackage requireSkill(Long skillId) {
        SkillPackage skill = packageMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 不存在");
        }
        return skill;
    }

    private String normalizePreviewPath(String path) {
        String normalized = defaultString(path).trim().replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 文件不存在");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 文件不存在");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 文件不存在");
        }
        return String.join("/", segments);
    }

    private byte[] readArchiveFile(byte[] archiveBytes, String relativePath) {
        List<ArchiveFile> files = readArchiveFiles(archiveBytes);
        ArchiveFile skillMdFile = files.stream()
                .filter(file -> !file.directory())
                .filter(file -> filename(file.path()).equalsIgnoreCase(SKILL_MD))
                .min(Comparator
                        .comparingInt((ArchiveFile file) -> pathDepth(file.path()))
                        .thenComparing(ArchiveFile::path))
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包缺少 SKILL.md"));
        String rootDir = rootDir(skillMdFile.path());
        String archivePath = rootDir.isBlank() ? relativePath : rootDir + "/" + relativePath;
        return files.stream()
                .filter(file -> !file.directory())
                .filter(file -> archivePath.equals(file.path()))
                .findFirst()
                .map(ArchiveFile::bytes)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Skill 文件不存在"));
    }

    private List<ArchiveFile> readArchiveFiles(byte[] archiveBytes) {
        List<ArchiveFile> files = new ArrayList<>();
        try (ZipInputStream inputStream = new ZipInputStream(new ByteArrayInputStream(archiveBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = inputStream.getNextEntry()) != null) {
                String path = normalizeArchivePath(entry.getName());
                byte[] bytes = entry.isDirectory() ? new byte[0] : inputStream.readAllBytes();
                files.add(new ArchiveFile(path, entry.isDirectory(), bytes));
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包不是有效的 zip 文件");
        }
        return files;
    }

    private String normalizeArchivePath(String path) {
        String normalized = defaultString(path).trim().replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包包含不安全路径");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包包含不安全路径");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包包含不安全路径");
        }
        return String.join("/", segments);
    }

    private boolean isBinary(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return false;
        } catch (CharacterCodingException exception) {
            return true;
        }
    }

    private String rootDir(String path) {
        int slashIndex = path.lastIndexOf('/');
        return slashIndex < 0 ? "" : path.substring(0, slashIndex);
    }

    private String filename(String path) {
        int slashIndex = path.lastIndexOf('/');
        return slashIndex < 0 ? path : path.substring(slashIndex + 1);
    }

    private int pathDepth(String path) {
        return (int) path.chars().filter(character -> character == '/').count();
    }

    private String writeManifest(List<ParsedSkillFile> files) {
        try {
            return objectMapper.writeValueAsString(files);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Skill 文件清单序列化失败");
        }
    }

    private List<ParsedSkillFile> readManifest(String manifestJson) {
        try {
            return objectMapper.readValue(manifestJson, FILE_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Skill 文件清单解析失败");
        }
    }

    private String uniqueSkillKey(String name) {
        String base = slugify(name);
        if (base.isBlank()) {
            base = "skill-" + Integer.toHexString(name.hashCode());
        }
        String candidate = base;
        int index = 2;
        while (packageMapper.selectBySkillKey(candidate) != null) {
            candidate = base + "-" + index;
            index++;
        }
        return candidate;
    }

    private String slugify(String value) {
        String normalized = Normalizer.normalize(defaultString(value), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        String slug = normalized.replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return slug.length() > 80 ? slug.substring(0, 80).replaceAll("-+$", "") : slug;
    }

    private String requireZipFilename(String filename) {
        String value = defaultString(filename).trim();
        if (value.isBlank() || !value.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持上传 zip 格式 Skill 包");
        }
        return value;
    }

    private String requireName(String value) {
        String name = defaultString(value).trim();
        if (name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 名称不能为空");
        }
        return name;
    }

    private String defaultContentType(String value) {
        return defaultString(value).isBlank() ? "application/zip" : value.trim();
    }

    private String buildObjectKey(String filename, OffsetDateTime now) {
        return "skill-packages/" + now.format(DateTimeFormatter.BASIC_ISO_DATE) + "/"
                + UUID.randomUUID() + "-" + filename;
    }

    private SkillPackageItem toItem(SkillPackage skill) {
        return new SkillPackageItem(
                skill.getId(),
                skill.getSkillKey(),
                skill.getName(),
                skill.getDescription(),
                skill.getOriginalFilename(),
                skill.getSkillMdContent(),
                skill.getFileCount(),
                skill.getUpdatedAt()
        );
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record ArchiveFile(
            String path,
            boolean directory,
            byte[] bytes
    ) {
    }
}
