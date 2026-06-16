# 完整 Skill Zip 同步到 Sandbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户上传完整 Skill zip 后，后端保留原始 zip，同时解压并把解压后的文件写入对象存储以支持管理页预览；聊天时用户勾选 Skill 后，后端把原始 zip 的临时下载地址发送给 sandbox，sandbox 保存 zip、执行解压，并把完整 Skill 内容放到 `/workspace/skills/{skillKey}`，让 docx 等复杂 Skill 能读取 `references/`、`scripts/`、模板和其他资源。

**Architecture:** Skill 导入链路仍以 MinIO 中的原始 zip 为权威包；Java 后端在确认导入时解析 zip、计算 zip sha256、逐文件上传解压内容并记录 manifest；聊天运行时通过 `SkillRuntimeContext` 携带 Skill 包同步元数据，在 LLM 首次调用前确保 sandbox session 存在并调用 sandbox-service 新增的同步接口；sandbox-service host 层代理 `/internal/sandbox/sessions/{sessionId}/skills/sync-zip`，agent 层下载 zip 到 `/workspace/skills/.archives/{skillKey}.zip`，校验 sha256 后安全解压到 `/workspace/skills/{skillKey}`。

**Tech Stack:** Java 21、Spring Boot 4、MyBatis Plus、Flyway、MinIO Java SDK、JUnit 5、Mockito、Go 1.24、net/http、archive/zip、httptest。

---

## File Map

- Modify: `backend/agent-hub-server/src/main/resources/db/migration/V12__init_skill_library.sql`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V14__full_skill_zip_sync.sql`
- Modify: `backend/agent-hub-server/src/test/java/com/agenthub/server/skill/SkillMigrationSmokeTest.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/entity/SkillPackage.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/entity/SkillImport.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/entity/SkillFileManifest.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/ParsedSkillArchive.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/ParsedSkillFile.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/StoredSkillFile.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/SkillArchiveParser.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/SkillRuntimeContext.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/SkillRuntimePackage.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/impl/SkillPackageServiceImpl.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/impl/SkillRuntimeServiceImpl.java`
- Modify: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/importing/SkillArchiveParserTest.java`
- Modify: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/service/SkillPackageServiceImplTest.java`
- Modify: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/service/SkillRuntimeServiceTest.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/object/ObjectStorageClient.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/object/MinioObjectStorageClient.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/SandboxClient.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/HttpSandboxClient.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/dto/SkillZipSyncRequest.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/dto/SkillZipSyncResult.java`
- Create: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/client/HttpSandboxClientTest.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`
- Modify: `sandbox-service/internal/agent/server.go`
- Modify: `sandbox-service/internal/agent/files.go`
- Create: `sandbox-service/internal/agent/skill_sync.go`
- Create: `sandbox-service/internal/agent/skill_sync_test.go`
- Modify: `sandbox-service/internal/host/api.go`
- Modify: `sandbox-service/internal/host/api_test.go`

## Contracts

- 原始 zip 保留在现有 `skill_package.bucket_name + skill_package.object_key`，聊天同步只使用这个 zip，不拼接解压后的碎片文件。
- 解压后的预览文件写入对象存储，object key 使用 `skill-packages/extracted/{importId}/{relativePath}`。目录不上传对象，只写 manifest 行。
- `skill_package.zip_sha256` 与 `skill_import.zip_sha256` 保存原始 zip SHA-256；`skill_file_manifest.bucket_name/object_key/content_type` 保存解压后文件对象信息。
- sandbox 内 zip 保存到 `/workspace/skills/.archives/{skillKey}.zip`，解压目录固定为 `/workspace/skills/{skillKey}`。
- Java 调用 sandbox-service host endpoint：`POST /internal/sandbox/sessions/{sessionId}/skills/sync-zip`。
- sandbox-service agent endpoint：`POST /skills/sync-zip`。
- 同步失败必须在 LLM 首次请求前失败，并将 assistant message 标为 `FAILED`，避免模型进入长工具循环。
- 兼容旧数据：如果历史 `skill_file_manifest.object_key` 为空，管理页预览回退到从原始 zip 读取文件。

## Task 1: 数据库与实体模型

**Files:**
- Modify: `backend/agent-hub-server/src/test/java/com/agenthub/server/skill/SkillMigrationSmokeTest.java`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V14__full_skill_zip_sync.sql`
- Modify: `backend/agent-hub-server/src/main/resources/db/migration/V12__init_skill_library.sql`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/entity/SkillPackage.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/entity/SkillImport.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/entity/SkillFileManifest.java`

- [ ] Step 1: 先更新迁移冒烟测试，断言新增列存在。测试代码形状如下：

```java
assertThat(columnsByTable.get("skill_package")).contains("zip_sha256");
assertThat(columnsByTable.get("skill_import")).contains("zip_sha256");
assertThat(columnsByTable.get("skill_file_manifest"))
        .contains("bucket_name", "object_key", "content_type");
```

- [ ] Step 2: 运行 `mvn -pl agent-hub-server -Dtest=SkillMigrationSmokeTest test`，确认测试失败。

- [ ] Step 3: 新增 `V14__full_skill_zip_sync.sql`，为已有库补列；同时修改 `V12__init_skill_library.sql`，让新建环境直接得到完整结构。

```sql
ALTER TABLE skill_package
    ADD COLUMN IF NOT EXISTS zip_sha256 VARCHAR(64);

ALTER TABLE skill_import
    ADD COLUMN IF NOT EXISTS zip_sha256 VARCHAR(64);

ALTER TABLE skill_file_manifest
    ADD COLUMN IF NOT EXISTS bucket_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS object_key VARCHAR(512),
    ADD COLUMN IF NOT EXISTS content_type VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_skill_file_object_key
    ON skill_file_manifest (bucket_name, object_key);
```

- [ ] Step 4: 给三个实体添加字段和标准 getter/setter。字段名按 MyBatis Plus 下划线映射约定写成 Java camelCase：

```java
private String zipSha256;
private String bucketName;
private String objectKey;
private String contentType;
```

- [ ] Step 5: 再次运行 `mvn -pl agent-hub-server,agent-hub-skill -am -Dtest=SkillMigrationSmokeTest test`，确认通过。

## Task 2: Skill zip 解析、解压文件上传与管理预览

**Files:**
- Modify: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/importing/SkillArchiveParserTest.java`
- Modify: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/service/SkillPackageServiceImplTest.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/ParsedSkillArchive.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/ParsedSkillFile.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/StoredSkillFile.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/importing/SkillArchiveParser.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/impl/SkillPackageServiceImpl.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/object/ObjectStorageClient.java`
- Modify: `backend/agent-hub-storage/src/main/java/com/agenthub/storage/object/MinioObjectStorageClient.java`

- [ ] Step 1: 在 `SkillArchiveParserTest` 增加断言：解析结果包含原始 zip sha256、每个普通文件的 bytes、content type 和 sha256；目录 bytes 为空数组。

```java
ParsedSkillArchive archive = parser.parse(zipBytes, "docx.zip");

assertThat(archive.zipSha256()).hasSize(64);
assertThat(archive.files())
        .filteredOn(file -> file.relativePath().equals("scripts/render_docx.py"))
        .singleElement()
        .satisfies(file -> {
            assertThat(file.directory()).isFalse();
            assertThat(file.bytes()).contains("python".getBytes(StandardCharsets.UTF_8));
            assertThat(file.contentType()).isEqualTo("text/x-python");
        });
```

- [ ] Step 2: 在 `SkillPackageServiceImplTest` 增加导入测试，断言确认导入后会上传解压文件、manifest 保存对象键、预览读取解压后的对象而不是重新读取 zip。

```java
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
```

- [ ] Step 3: 运行 `mvn -pl agent-hub-skill -Dtest=SkillArchiveParserTest,SkillPackageServiceImplTest test`，确认测试失败。

- [ ] Step 4: 扩展解析记录。`ParsedSkillFile` 保存本次解析期间的 bytes，但不要直接把它序列化到 `skill_import.manifest_json`；manifest 使用新的 `StoredSkillFile`。

```java
public record ParsedSkillArchive(
        String name,
        String description,
        String rootDir,
        String skillMdPath,
        String skillMdContent,
        String zipSha256,
        List<ParsedSkillFile> files
) {
    public ParsedSkillArchive {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
```

```java
public record ParsedSkillFile(
        String relativePath,
        boolean directory,
        long sizeBytes,
        String sha256,
        String contentType,
        byte[] bytes
) {
    public ParsedSkillFile {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
```

```java
public record StoredSkillFile(
        String relativePath,
        boolean directory,
        long sizeBytes,
        String sha256,
        String bucketName,
        String objectKey,
        String contentType
) {
}
```

- [ ] Step 5: 修改 `SkillArchiveParser`：解析 zip 前计算 zip sha256；`toParsedFile` 为普通文件写入 bytes 和 content type。

```java
return new ParsedSkillArchive(
        name,
        description,
        rootDir,
        SKILL_MD,
        skillMdContent,
        sha256(zipBytes),
        files
);
```

```java
private ParsedSkillFile toParsedFile(ArchiveEntry entry, String rootDir) {
    String relativePath = stripRoot(entry.normalizedPath(), rootDir);
    return new ParsedSkillFile(
            relativePath,
            entry.directory(),
            entry.sizeBytes(),
            entry.sha256(),
            entry.directory() ? null : contentType(relativePath),
            entry.bytes()
    );
}
```

- [ ] Step 6: 扩展对象存储接口和 MinIO 实现，支持服务端上传解压文件。

```java
void putBytes(String bucket, String objectKey, String contentType, byte[] bytes);

default void putBytes(String objectKey, String contentType, byte[] bytes) {
    putBytes(null, objectKey, contentType, bytes);
}
```

```java
@Override
public void putBytes(String bucket, String objectKey, String contentType, byte[] bytes) {
    byte[] payload = bytes == null ? new byte[0] : bytes;
    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(payload)) {
        internalClient.putObject(PutObjectArgs.builder()
                .bucket(resolveBucket(bucket))
                .object(objectKey)
                .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                .stream(inputStream, payload.length, -1)
                .build());
    } catch (Exception exception) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, STORAGE_ERROR_MESSAGE);
    }
}
```

- [ ] Step 7: 在 `SkillPackageServiceImpl.confirmImport` 中读取原始 zip 一次、解析一次、上传解压文件一次，并把 `StoredSkillFile` 写入 `manifest_json`。

```java
byte[] zipBytes = objectStorageClient.readBytes(skillImport.getObjectKey());
ParsedSkillArchive archive = archiveParser.parse(zipBytes, skillImport.getOriginalFilename());
List<StoredSkillFile> storedFiles = storeExtractedFiles(skillImport, archive);
fillParsedImport(skillImport, archive, storedFiles);
```

```java
private List<StoredSkillFile> storeExtractedFiles(SkillImport skillImport, ParsedSkillArchive archive) {
    List<StoredSkillFile> storedFiles = new ArrayList<>();
    for (ParsedSkillFile parsedFile : archive.files()) {
        if (parsedFile.directory()) {
            storedFiles.add(new StoredSkillFile(parsedFile.relativePath(), true, 0L, null, null, null, null));
            continue;
        }
        String objectKey = "skill-packages/extracted/" + skillImport.getId() + "/" + parsedFile.relativePath();
        objectStorageClient.putBytes(skillImport.getBucketName(), objectKey, parsedFile.contentType(), parsedFile.bytes());
        storedFiles.add(new StoredSkillFile(
                parsedFile.relativePath(),
                false,
                parsedFile.sizeBytes(),
                parsedFile.sha256(),
                skillImport.getBucketName(),
                objectKey,
                parsedFile.contentType()
        ));
    }
    return storedFiles;
}
```

- [ ] Step 8: `replaceFiles` 从 `StoredSkillFile` 读 manifest，并写入 `bucketName/objectKey/contentType`；`fillPackageFromImport` 写入 `zipSha256`。

```java
file.setBucketName(storedFile.bucketName());
file.setObjectKey(storedFile.objectKey());
file.setContentType(storedFile.contentType());
```

- [ ] Step 9: `fileContent` 优先读取解压后对象，旧数据回退到原始 zip。

```java
byte[] fileBytes;
if (manifest.getObjectKey() != null && !manifest.getObjectKey().isBlank()) {
    fileBytes = objectStorageClient.readBytes(manifest.getBucketName(), manifest.getObjectKey());
} else {
    byte[] archiveBytes = objectStorageClient.readBytes(skill.getBucketName(), skill.getObjectKey());
    fileBytes = readArchiveFile(archiveBytes, normalizedPath);
}
```

- [ ] Step 10: 运行 `mvn -pl agent-hub-skill -Dtest=SkillArchiveParserTest,SkillPackageServiceImplTest test`，确认通过。

## Task 3: sandbox-service agent 层下载并解压 Skill zip

**Files:**
- Create: `sandbox-service/internal/agent/skill_sync_test.go`
- Create: `sandbox-service/internal/agent/skill_sync.go`
- Modify: `sandbox-service/internal/agent/server.go`
- Modify: `sandbox-service/internal/agent/files.go`

- [ ] Step 1: 写 agent 层失败测试：`POST /skills/sync-zip` 能从 URL 下载 zip，保存 zip 到 `/workspace/skills/.archives/demo-skill.zip`，并把文件解压到 `/workspace/skills/demo-skill`。

```go
func TestServerSyncSkillZipDownloadsAndExtracts(t *testing.T) {
    root := t.TempDir()
    zipBytes := testZip(t, map[string]string{
        "SKILL.md":              "# Demo",
        "references/style.md":   "style",
        "scripts/render_docx.py": "print('ok')",
    })
    zipServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        _, _ = w.Write(zipBytes)
    }))
    defer zipServer.Close()

    server := NewServer(NewExecutor(1024), NewFileService(root))
    body := bytes.NewBufferString(`{"skills":[{"skillId":6,"skillKey":"demo-skill","name":"Demo","zipDownloadUrl":"` +
        zipServer.URL + `","zipSha256":"` + sha256Hex(zipBytes) + `","targetDir":"/workspace/skills/demo-skill"}]}`)
    req := httptest.NewRequest(http.MethodPost, "/skills/sync-zip", body)
    rec := httptest.NewRecorder()

    server.Router().ServeHTTP(rec, req)

    if rec.Code != http.StatusOK {
        t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
    }
    assertFileContent(t, filepath.Join(root, "skills", "demo-skill", "references", "style.md"), "style")
    assertFileContent(t, filepath.Join(root, "skills", ".archives", "demo-skill.zip"), string(zipBytes))
}
```

- [ ] Step 2: 写 agent 层安全测试：拒绝 zip slip、绝对路径、Windows 盘符路径、目标目录不是 `/workspace/skills/{skillKey}`、sha256 不匹配、超过文件数或解压总大小限制。

```go
func TestServerSyncSkillZipRejectsTraversalEntry(t *testing.T) {
    root := t.TempDir()
    zipBytes := testZip(t, map[string]string{"../evil.txt": "bad"})
    zipServer := serveBytes(t, zipBytes)
    server := NewServer(NewExecutor(1024), NewFileService(root))

    req := httptest.NewRequest(http.MethodPost, "/skills/sync-zip", strings.NewReader(syncBody(zipServer.URL, sha256Hex(zipBytes))))
    rec := httptest.NewRecorder()
    server.Router().ServeHTTP(rec, req)

    if rec.Code != http.StatusBadRequest {
        t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
    }
    if _, err := os.Stat(filepath.Join(root, "evil.txt")); !os.IsNotExist(err) {
        t.Fatalf("unexpected escaped file, err = %v", err)
    }
}
```

- [ ] Step 3: 运行 `go test ./internal/agent -run SkillZip`，确认测试失败。

- [ ] Step 4: 给 `Server.Router` 增加 agent endpoint。

```go
mux.HandleFunc("POST /skills/sync-zip", s.handleSyncSkillZip)
```

- [ ] Step 5: 新建 `skill_sync.go`，实现 DTO、handler、下载、sha256 校验和安全解压。

```go
type SkillZipSyncRequest struct {
    Skills []SkillZipSyncItem `json:"skills"`
}

type SkillZipSyncItem struct {
    SkillID        int64  `json:"skillId"`
    SkillKey       string `json:"skillKey"`
    Name           string `json:"name"`
    ZipDownloadURL string `json:"zipDownloadUrl"`
    ZipSHA256      string `json:"zipSha256"`
    TargetDir      string `json:"targetDir"`
}

type SkillZipSyncItemResult struct {
    SkillID     int64  `json:"skillId"`
    SkillKey    string `json:"skillKey"`
    TargetDir   string `json:"targetDir"`
    ArchivePath string `json:"archivePath"`
    FileCount   int    `json:"fileCount"`
    TotalBytes  int64  `json:"totalBytes"`
}
```

```go
func (s *Server) handleSyncSkillZip(w http.ResponseWriter, r *http.Request) {
    var req SkillZipSyncRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        writeError(w, http.StatusBadRequest, "请求体不是有效的 JSON")
        return
    }
    results, err := s.syncSkillZips(r.Context(), req)
    if err != nil {
        writeError(w, http.StatusBadRequest, "同步 Skill 包失败: "+err.Error())
        return
    }
    writeJSON(w, http.StatusOK, map[string]any{"ok": true, "skills": results})
}
```

- [ ] Step 6: 在 `FileService` 增加目标路径解析方法，保证只能写入固定 Skill 目录。

```go
func (s *FileService) ResolveSkillTarget(skillKey string, targetDir string) (string, string, error) {
    if !validSkillKey(skillKey) {
        return "", "", errors.New("Skill key 不合法")
    }
    expectedGuestPath := "/workspace/skills/" + skillKey
    cleanedTarget := filepath.ToSlash(filepath.Clean(strings.TrimSpace(targetDir)))
    if cleanedTarget != expectedGuestPath {
        return "", "", errors.New("Skill 只能同步到 /workspace/skills/{skillKey}")
    }
    targetHostPath, err := s.resolveGuestWorkspacePath(expectedGuestPath)
    if err != nil {
        return "", "", err
    }
    archiveHostPath, err := s.resolveGuestWorkspacePath("/workspace/skills/.archives/" + skillKey + ".zip")
    if err != nil {
        return "", "", err
    }
    return targetHostPath, archiveHostPath, nil
}
```

- [ ] Step 7: 解压实现使用 `archive/zip`，每个 entry 先通过 lexical 校验，再校验最终 host path 在目标目录内。

```go
func safeZipEntryPath(name string) (string, error) {
    cleaned := path.Clean(strings.TrimSpace(strings.ReplaceAll(name, "\\", "/")))
    if cleaned == "." || strings.HasPrefix(cleaned, "/") || strings.HasPrefix(cleaned, "../") ||
        cleaned == ".." || isWindowsDrivePath(cleaned) {
        return "", errors.New("zip 包含不安全路径")
    }
    return cleaned, nil
}
```

- [ ] Step 8: 同步前清空目标目录，并重新创建目录；zip 保留在 `.archives` 下。删除前先确保父目录不经过 symlink。

```go
if err := os.MkdirAll(filepath.Dir(archiveHostPath), 0o755); err != nil {
    return SkillZipSyncItemResult{}, err
}
if err := os.WriteFile(archiveHostPath, zipBytes, 0o644); err != nil {
    return SkillZipSyncItemResult{}, err
}
if err := os.RemoveAll(targetHostPath); err != nil {
    return SkillZipSyncItemResult{}, err
}
if err := os.MkdirAll(targetHostPath, 0o755); err != nil {
    return SkillZipSyncItemResult{}, err
}
```

- [ ] Step 9: 运行 `go test ./internal/agent -run SkillZip`，确认通过。

## Task 4: sandbox-service host 代理与 Java SandboxClient

**Files:**
- Modify: `sandbox-service/internal/host/api_test.go`
- Modify: `sandbox-service/internal/host/api.go`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/SandboxClient.java`
- Modify: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/HttpSandboxClient.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/dto/SkillZipSyncRequest.java`
- Create: `backend/agent-hub-sandbox/src/main/java/com/agenthub/sandbox/client/dto/SkillZipSyncResult.java`
- Create: `backend/agent-hub-sandbox/src/test/java/com/agenthub/sandbox/client/HttpSandboxClientTest.java`

- [ ] Step 1: 写 host 层代理测试：请求 `/internal/sandbox/sessions/{id}/skills/sync-zip`，guest 收到 `/skills/sync-zip`，body 原样转发；不存在 session 返回 404。

```go
req := httptest.NewRequest(http.MethodPost,
    "/internal/sandbox/sessions/"+session.ID+"/skills/sync-zip",
    strings.NewReader(`{"skills":[{"skillId":6,"skillKey":"docx","targetDir":"/workspace/skills/docx"}]}`))
rec := httptest.NewRecorder()

router.ServeHTTP(rec, req)

if rec.Code != http.StatusOK {
    t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
}
```

- [ ] Step 2: 实现 host 路由和 handler。

```go
mux.HandleFunc("POST /internal/sandbox/sessions/{sessionId}/skills/sync-zip", server.handleSyncSkillZip)
```

```go
func (s *apiServer) handleSyncSkillZip(w http.ResponseWriter, r *http.Request) {
    session, ok := s.beginSessionUse(w, r.PathValue("sessionId"))
    if !ok {
        return
    }
    defer s.manager.EndUse(session.ID)

    body, err := io.ReadAll(io.LimitReader(r.Body, 2<<20))
    if err != nil {
        writeError(w, http.StatusBadRequest, "读取请求体失败")
        return
    }
    status, responseBody, err := s.proxyJSONResponse(
        r,
        http.MethodPost,
        joinAgentURL(session.AgentURL, "/skills/sync-zip"),
        bytes.NewReader(body),
    )
    if err != nil {
        writeError(w, http.StatusBadGateway, "同步 Skill 包代理失败")
        return
    }
    w.Header().Set("Content-Type", "application/json; charset=utf-8")
    w.WriteHeader(status)
    _, _ = w.Write(responseBody)
}
```

- [ ] Step 3: 写 Java client 测试，断言 HTTP 方法、路径、Authorization header 和 body。

```java
SkillZipSyncRequest request = new SkillZipSyncRequest(List.of(
        new SkillZipSyncRequest.Skill(
                6L,
                "docx",
                "docx",
                "https://minio.example.com/docx.zip",
                "a".repeat(64),
                "/workspace/skills/docx"
        )
));

SkillZipSyncResult result = client.syncSkillZips("sbx_1", request);

assertThat(result.ok()).isTrue();
assertThat(capturedPath.get()).isEqualTo("/internal/sandbox/sessions/sbx_1/skills/sync-zip");
```

- [ ] Step 4: 新增 Java DTO。

```java
public record SkillZipSyncRequest(List<Skill> skills) {
    public SkillZipSyncRequest {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public record Skill(
            Long skillId,
            String skillKey,
            String name,
            String zipDownloadUrl,
            String zipSha256,
            String targetDir
    ) {
    }
}
```

```java
public record SkillZipSyncResult(boolean ok, List<SkillResult> skills) {
    public SkillZipSyncResult {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public record SkillResult(
            Long skillId,
            String skillKey,
            String targetDir,
            String archivePath,
            Integer fileCount,
            Long totalBytes
    ) {
    }
}
```

- [ ] Step 5: 扩展 `SandboxClient` 和 `HttpSandboxClient`。

```java
SkillZipSyncResult syncSkillZips(String sandboxSessionId, SkillZipSyncRequest request);
```

```java
@Override
public SkillZipSyncResult syncSkillZips(String sandboxSessionId, SkillZipSyncRequest request) {
    try {
        String body = OBJECT_MAPPER.writeValueAsString(request);
        HttpRequest httpRequest = requestBuilder("/internal/sandbox/sessions/"
                + encodePath(sandboxSessionId) + "/skills/sync-zip")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json; charset=utf-8")
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), "同步 Skill 包到沙箱失败");
        return OBJECT_MAPPER.readValue(response.body(), SkillZipSyncResult.class);
    } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步 Skill 包到沙箱被中断");
    } catch (IOException exception) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步 Skill 包到沙箱失败");
    }
}
```

- [ ] Step 6: 运行 `go test ./internal/host -run SyncSkillZip` 和 `mvn -pl agent-hub-sandbox -Dtest=HttpSandboxClientTest test`，确认通过。

## Task 5: 聊天运行时在 LLM 前同步完整 Skill 包

**Files:**
- Modify: `backend/agent-hub-skill/src/test/java/com/agenthub/skill/service/SkillRuntimeServiceTest.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`
- Create: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/SkillRuntimePackage.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/SkillRuntimeContext.java`
- Modify: `backend/agent-hub-skill/src/main/java/com/agenthub/skill/service/impl/SkillRuntimeServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`

- [ ] Step 1: 更新 `SkillRuntimeServiceTest`：上下文应包含同步包元数据，prompt 应告诉模型完整 Skill 已在 `/workspace/skills/{skillKey}`。

```java
SkillRuntimeContext context = service.buildRuntimeContext(null, List.of(6L));

assertThat(context.packages())
        .singleElement()
        .satisfies(skill -> {
            assertThat(skill.skillId()).isEqualTo(6L);
            assertThat(skill.skillKey()).isEqualTo("docx");
            assertThat(skill.objectKey()).isEqualTo("skill-packages/docx.zip");
            assertThat(skill.zipSha256()).hasSize(64);
        });
assertThat(context.prompt()).contains("/workspace/skills/docx");
```

- [ ] Step 2: 更新 `ChatServiceImplTest`：选择 Skill 后，在 LLM stream 前必须按顺序调用 `ensureSession`、`presignGet`、`syncSkillZips`。

```java
InOrder inOrder = inOrder(sandboxSessionService, objectStorageClient, sandboxClient, llmClient);

inOrder.verify(sandboxSessionService).ensureSession(7L, 100L);
inOrder.verify(objectStorageClient).presignGet(
        eq("agent-hub"),
        eq("skill-packages/docx.zip"),
        eq(Duration.ofMinutes(10))
);
inOrder.verify(sandboxClient).syncSkillZips(eq("sbx_1"), any(SkillZipSyncRequest.class));
inOrder.verify(llmClient).stream(any(LlmStreamRequest.class), any());
```

- [ ] Step 3: 增加失败测试：`syncSkillZips` 抛异常时，LLM client 不应被调用，assistant message 最终为 `FAILED`。

```java
doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "同步 Skill 包到沙箱失败"))
        .when(sandboxClient)
        .syncSkillZips(eq("sbx_1"), any(SkillZipSyncRequest.class));

service.stream(7L, requestWithSkill(6L), true);

verify(llmClient, never()).stream(any(), any());
verify(messageMapper).updateById(argThat(message ->
        "FAILED".equals(message.getStatus())
                && message.getErrorMessage().contains("同步 Skill 包到沙箱失败")
));
```

- [ ] Step 4: 运行 `mvn -pl agent-hub-chat,agent-hub-skill -am -Dtest=ChatServiceImplTest,SkillRuntimeServiceTest test`，确认测试失败。

- [ ] Step 5: 新增运行时包记录，并扩展 `SkillRuntimeContext`。

```java
public record SkillRuntimePackage(
        Long skillId,
        String skillKey,
        String name,
        String bucketName,
        String objectKey,
        String zipSha256
) {
}
```

```java
public record SkillRuntimeContext(
        String prompt,
        List<Long> skillIds,
        List<SkillRuntimePackage> packages
) {
    public SkillRuntimeContext {
        prompt = prompt == null ? "" : prompt;
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        packages = packages == null ? List.of() : List.copyOf(packages);
    }
}
```

- [ ] Step 6: `SkillRuntimeServiceImpl` 构建 packages，并在 prompt 中公开 sandbox 路径。

```java
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
return new SkillRuntimeContext(buildPrompt(agentId == null, skills), skills.stream().map(SkillPackage::getId).toList(), packages);
```

```java
builder.append("sandbox 路径：/workspace/skills/")
        .append(skill.getSkillKey())
        .append("，可读取该目录下的 SKILL.md、references、scripts 和模板文件。\n");
```

- [ ] Step 7: `ChatServiceImpl` 的 `PreparedChat` 携带完整 `SkillRuntimeContext`；`streamLlm` 参数由 `List<Long> skillIds` 改为 `SkillRuntimeContext skillContext`，工具上下文仍使用 `skillContext.skillIds()`。

```java
private record PreparedChat(
        ChatConversation conversation,
        ChatMessage userMessage,
        ChatMessage assistantMessage,
        List<AttachmentReference> images,
        ConversationContext context,
        SkillRuntimeContext skillContext
) {
}
```

- [ ] Step 8: 在 `streamLlm` 的 try 块最前面同步 Skill 包。同步完成后再构建可用工具、再请求 LLM。

```java
try {
    syncSkillsToSandbox(userId, conversationId, skillContext);
    availableTools = listTools(toolContext);
    LlmClient client = llmClientFactory.apply(runtimeConfig.protocolType());
    loopMessages = withSandboxPathRules(messages, availableTools);
    int toolCallCount = 0;
    while (!finished.get()) {
        StreamAttemptOutcome outcome = streamOnce(client, runtimeConfig, loopMessages, reasoningRequested,
                availableTools, emitter, assistantMessage, content, reasoningContent, processRecorder, finished);
        if (finished.get() || outcome.toolCalls().isEmpty()) {
            return;
        }
        toolCallCount += outcome.toolCalls().size();
    }
}
```

```java
private void syncSkillsToSandbox(Long userId, Long conversationId, SkillRuntimeContext skillContext) {
    if (skillContext == null || skillContext.packages().isEmpty()) {
        return;
    }
    if (sandboxSessionService == null || sandboxClient == null || objectStorageClient == null) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "沙箱同步服务不可用");
    }
    String sandboxSessionId = sandboxSessionService.ensureSession(userId, conversationId);
    List<SkillZipSyncRequest.Skill> skills = skillContext.packages().stream()
            .map(skill -> new SkillZipSyncRequest.Skill(
                    skill.skillId(),
                    skill.skillKey(),
                    skill.name(),
                    objectStorageClient.presignGet(skill.bucketName(), skill.objectKey(), Duration.ofMinutes(10)),
                    skill.zipSha256(),
                    "/workspace/skills/" + skill.skillKey()
            ))
            .toList();
    sandboxClient.syncSkillZips(sandboxSessionId, new SkillZipSyncRequest(skills));
}
```

- [ ] Step 9: 更新 `ChatServiceImpl` 构造器。生产构造器注入 `SandboxSessionService`、`SandboxClient`、`ObjectStorageClient`；测试专用构造器继续可传 null，但选择 Skill 的测试必须传入 mock。

- [ ] Step 10: 运行 `mvn -pl agent-hub-chat,agent-hub-skill -am -Dtest=ChatServiceImplTest,SkillRuntimeServiceTest test`，确认通过。

## Task 6: 集成验证与原始场景复测

**Files:**
- No source files expected beyond tasks above.

- [ ] Step 1: 跑后端单元测试。

```powershell
mvn -pl agent-hub-server,agent-hub-storage,agent-hub-sandbox,agent-hub-skill,agent-hub-chat -am test
```

- [ ] Step 2: 跑 sandbox-service 测试。

```powershell
go test ./...
```

- [ ] Step 3: 启动本地后端、sandbox-service 和 MinIO，上传包含 `SKILL.md`、`references/`、`scripts/`、模板文件的 docx Skill zip。确认：

```text
skill_package.object_key      = 原始 zip 对象键
skill_package.zip_sha256      = 64 位 sha256
skill_file_manifest.object_key = skill-packages/extracted/1/references/style.md
```

- [ ] Step 4: 在管理页预览 `references` 或 `scripts` 下的文本文件，确认后端读取的是解压后的对象存储文件。可通过 mock 日志或断点确认没有重新读取原始 zip。

- [ ] Step 5: 使用原始场景复测：

```text
用户消息：帮我写一个南宁市政府的红头文件，内容是关于端午放假通知
Skill：docx
thinkingEnabled：true
```

- [ ] Step 6: 在 sandbox 中验证完整目录存在。至少应能通过现有 `sandbox.list_files` 看到：

```text
/workspace/skills/docx/SKILL.md
/workspace/skills/docx/references/style.md
/workspace/skills/docx/scripts/render_docx.py
/workspace/skills/.archives/docx.zip
```

- [ ] Step 7: 验证对话不再出现“只能读取 SKILL.md、缺 references/scripts、找不到模板”的失败循环。若仍出现 `sandbox.exec` 命令策略失败，记录为独立问题，保留本次同步链路的成功证据。

- [ ] Step 8: 检查新增失败行为：如果 MinIO presign 失败、sandbox 下载失败、sha256 不匹配或解压失败，assistant message 应为 `FAILED`，SSE 应返回清晰中文错误，且不调用 LLM。

## Implementation Notes

- 不在 Java 中把解压后的所有文件再传给 LLM；prompt 只告诉模型 sandbox 目录位置，避免上下文膨胀。
- 不通过 `sandbox.exec` 运行 `unzip docx.zip -d /workspace/skills/docx` 完成同步。同步由 sandbox-service agent endpoint 执行，绕开命令安全策略和模型行为不确定性。
- `skill_file_manifest.object_key` 允许为空是为了兼容历史数据；新导入数据必须写入对象键。
- sandbox 解压时不要信任 zip entry 名称；每个 entry 都要经过 lexical 校验和最终 host path containment 校验。
- 同一个 sandbox session 多轮使用同一个 Skill 时可以重复同步；实现先以幂等覆盖为准，后续再考虑按 sha256 做 skip 优化。

## Completion Criteria

- 原始 zip 保留在 MinIO，解压后的文件也以独立对象形式保存在 MinIO 并可用于管理页预览。
- 聊天时选择 Skill 会在 LLM 调用前把原始 zip 同步到 sandbox，并解压到 `/workspace/skills/{skillKey}`。
- sandbox 内保留 zip 副本 `/workspace/skills/.archives/{skillKey}.zip`。
- docx Skill 可访问完整 skill 包内容，而不是只能访问 `SKILL.md`。
- 所有新增 Java 和 Go 单元测试通过，原始端午放假通知场景能明确验证同步链路成功。
