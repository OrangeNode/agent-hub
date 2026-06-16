package com.agenthub.skill.importing;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SkillArchiveParser {

    private static final String SKILL_MD = "SKILL.md";

    public ParsedSkillArchive parse(byte[] zipBytes, String fallbackFilename) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包不是有效的 zip 文件");
        }
        String zipSha256 = sha256(zipBytes);
        List<ArchiveEntry> entries = readEntries(zipBytes);
        ArchiveEntry skillMdEntry = chooseSkillMd(entries);
        String rootDir = rootDir(skillMdEntry.normalizedPath());
        String skillMdContent = new String(skillMdEntry.bytes(), StandardCharsets.UTF_8);
        if (skillMdContent.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SKILL.md 内容不能为空");
        }

        Map<String, String> frontmatter = parseFrontmatter(skillMdContent);
        String name = firstText(
                frontmatter.get("name"),
                firstHeading(skillMdContent),
                filenameWithoutZip(fallbackFilename)
        );
        String description = blankToNull(frontmatter.get("description"));
        List<ParsedSkillFile> files = entries.stream()
                .filter(entry -> isInSkillRoot(entry.normalizedPath(), rootDir))
                .map(entry -> toParsedFile(entry, rootDir))
                .sorted(Comparator.comparing(ParsedSkillFile::relativePath))
                .toList();

        return new ParsedSkillArchive(name, description, rootDir, SKILL_MD, skillMdContent, zipSha256, files);
    }

    private List<ArchiveEntry> readEntries(byte[] zipBytes) {
        List<ArchiveEntry> entries = new ArrayList<>();
        try (ZipInputStream inputStream = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry zipEntry;
            while ((zipEntry = inputStream.getNextEntry()) != null) {
                String normalizedPath = normalizePath(zipEntry.getName());
                byte[] bytes = zipEntry.isDirectory() ? new byte[0] : readAll(inputStream);
                entries.add(new ArchiveEntry(
                        normalizedPath,
                        zipEntry.isDirectory(),
                        zipEntry.isDirectory() ? 0 : bytes.length,
                        zipEntry.isDirectory() ? null : sha256(bytes),
                        bytes
                ));
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包不是有效的 zip 文件");
        }
        return entries;
    }

    private ArchiveEntry chooseSkillMd(List<ArchiveEntry> entries) {
        return entries.stream()
                .filter(entry -> !entry.directory())
                .filter(entry -> filename(entry.normalizedPath()).equalsIgnoreCase(SKILL_MD))
                .min(Comparator
                        .comparingInt((ArchiveEntry entry) -> pathDepth(entry.normalizedPath()))
                        .thenComparing(ArchiveEntry::normalizedPath))
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "Skill 包缺少 SKILL.md"));
    }

    private String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
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

    private byte[] readAll(ZipInputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        inputStream.transferTo(outputStream);
        return outputStream.toByteArray();
    }

    private Map<String, String> parseFrontmatter(String content) {
        String[] lines = removeBom(content).split("\\R", -1);
        int start = firstNonBlankLine(lines);
        if (start < 0 || !"---".equals(lines[start].trim())) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = start + 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if ("---".equals(line)) {
                return values;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex <= 0) {
                continue;
            }
            String key = line.substring(0, colonIndex).trim().toLowerCase(Locale.ROOT);
            String value = stripQuotes(line.substring(colonIndex + 1).trim());
            values.put(key, value);
        }
        return Map.of();
    }

    private int firstNonBlankLine(String[] lines) {
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].isBlank()) {
                return index;
            }
        }
        return -1;
    }

    private String firstHeading(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") && trimmed.length() > 2) {
                return trimmed.substring(2).trim();
            }
        }
        return null;
    }

    private ParsedSkillFile toParsedFile(ArchiveEntry entry, String rootDir) {
        String relativePath = stripRoot(entry.normalizedPath(), rootDir);
        return new ParsedSkillFile(
                relativePath,
                entry.directory(),
                entry.sizeBytes(),
                entry.sha256(),
                entry.directory() ? null : contentType(relativePath),
                entry.directory() ? new byte[0] : entry.bytes()
        );
    }

    private String contentType(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT);
        if (filename(normalized).equalsIgnoreCase(SKILL_MD.toLowerCase(Locale.ROOT))
                || normalized.endsWith(".md")) {
            return "text/markdown";
        }
        if (normalized.endsWith(".py")) {
            return "text/x-python";
        }
        if (normalized.endsWith(".json")) {
            return "application/json";
        }
        if (normalized.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private boolean isInSkillRoot(String path, String rootDir) {
        return rootDir.isBlank() || path.equals(rootDir) || path.startsWith(rootDir + "/");
    }

    private String stripRoot(String path, String rootDir) {
        if (rootDir.isBlank()) {
            return path;
        }
        if (path.equals(rootDir)) {
            return "";
        }
        return path.substring(rootDir.length() + 1);
    }

    private String rootDir(String skillMdPath) {
        int slashIndex = skillMdPath.lastIndexOf('/');
        return slashIndex < 0 ? "" : skillMdPath.substring(0, slashIndex);
    }

    private String filename(String path) {
        int slashIndex = path.lastIndexOf('/');
        return slashIndex < 0 ? path : path.substring(slashIndex + 1);
    }

    private int pathDepth(String path) {
        return (int) path.chars().filter(character -> character == '/').count();
    }

    private String filenameWithoutZip(String filename) {
        String value = filename == null || filename.isBlank() ? "未命名 Skill" : filename.trim();
        if (value.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            value = value.substring(0, value.length() - 4);
        }
        return value.isBlank() ? "未命名 Skill" : value;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "未命名 Skill";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String removeBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2
                && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record ArchiveEntry(
            String normalizedPath,
            boolean directory,
            long sizeBytes,
            String sha256,
            byte[] bytes
    ) {
    }
}
