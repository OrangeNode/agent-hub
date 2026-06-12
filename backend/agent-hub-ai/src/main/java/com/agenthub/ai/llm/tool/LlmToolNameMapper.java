package com.agenthub.ai.llm.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class LlmToolNameMapper {

    private static final int MAX_MODEL_NAME_LENGTH = 64;
    private static final int HASH_LENGTH = 12;
    private static final Pattern MODEL_SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final Map<String, String> internalToModelName;
    private final Map<String, String> modelToInternalName;

    private LlmToolNameMapper(Map<String, String> internalToModelName, Map<String, String> modelToInternalName) {
        this.internalToModelName = Map.copyOf(internalToModelName);
        this.modelToInternalName = Map.copyOf(modelToInternalName);
    }

    public static LlmToolNameMapper from(List<LlmToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return new LlmToolNameMapper(Map.of(), Map.of());
        }
        Map<String, String> internalToModelName = new LinkedHashMap<>();
        Map<String, String> modelToInternalName = new HashMap<>();
        for (LlmToolDefinition tool : tools) {
            String internalName = tool.name();
            if (internalName == null || internalName.isBlank()) {
                throw new IllegalArgumentException("工具名称不能为空");
            }
            if (internalToModelName.containsKey(internalName)) {
                throw new IllegalArgumentException("重复的工具名称: " + internalName);
            }
            String modelName = toModelName(internalName, modelToInternalName);
            internalToModelName.put(internalName, modelName);
            modelToInternalName.put(modelName, internalName);
        }
        return new LlmToolNameMapper(internalToModelName, modelToInternalName);
    }

    public String toModelName(String internalName) {
        String mappedName = internalToModelName.get(internalName);
        if (mappedName != null) {
            return mappedName;
        }
        if (isModelSafeName(internalName)) {
            return internalName;
        }
        throw new IllegalArgumentException("工具调用名称未在当前工具定义中: " + internalName);
    }

    public String toInternalName(String modelName) {
        return modelToInternalName.getOrDefault(modelName, modelName);
    }

    public static boolean isModelSafeName(String name) {
        return name != null && MODEL_SAFE_NAME.matcher(name).matches();
    }

    private static String toModelName(String internalName, Map<String, String> usedModelNames) {
        if (isModelSafeName(internalName)) {
            if (usedModelNames.containsKey(internalName)) {
                throw new IllegalArgumentException("工具模型名称冲突: " + internalName);
            }
            return internalName;
        }

        String sanitized = sanitize(internalName);
        String hash = hash(internalName).substring(0, HASH_LENGTH);
        String modelName = truncatePrefix(sanitized, hash.length()) + "_" + hash;
        if (!isModelSafeName(modelName)) {
            throw new IllegalArgumentException("无法生成合法工具模型名称: " + internalName);
        }
        String existingInternalName = usedModelNames.get(modelName);
        if (existingInternalName != null && !existingInternalName.equals(internalName)) {
            throw new IllegalArgumentException("工具模型名称冲突: " + internalName + " -> " + modelName);
        }
        return modelName;
    }

    private static String sanitize(String internalName) {
        String sanitized = internalName.replaceAll("[^a-zA-Z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        return sanitized.isBlank() ? "tool" : sanitized;
    }

    private static String truncatePrefix(String prefix, int hashLength) {
        int maxPrefixLength = MAX_MODEL_NAME_LENGTH - hashLength - 1;
        if (prefix.length() <= maxPrefixLength) {
            return prefix;
        }
        return prefix.substring(0, maxPrefixLength).replaceAll("_+$", "");
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte valueByte : bytes) {
                builder.append(String.format("%02x", valueByte));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算工具名称哈希", exception);
        }
    }
}
