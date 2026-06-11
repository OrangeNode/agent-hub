package com.agenthub.system.operationlog.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

public class OperationLogSanitizer {

    private static final String MASK_VALUE = "******";
    private static final String UNSERIALIZABLE_JSON = "\"<unserializable>\"";
    private static final Set<String> DEFAULT_SENSITIVE_FIELDS = Set.of(
            "apikey",
            "internaltoken",
            "password",
            "token",
            "secret",
            "authorization",
            "content",
            "filecontent",
            "stdout",
            "stderr"
    );
    private static final Set<String> SENSITIVE_FIELD_KEYWORDS = Set.of(
            "apikey",
            "token",
            "secret",
            "password",
            "authorization"
    );

    private final ObjectMapper objectMapper;

    public OperationLogSanitizer() {
        this.objectMapper = createObjectMapper();
    }

    public String toSanitizedJson(Object value, int maxLength, String[] extraSensitiveFields) {
        if (value == null) {
            return null;
        }

        try {
            JsonNode sanitizedNode = sanitizeNode(objectMapper.valueToTree(value), sensitiveFields(extraSensitiveFields));
            return truncateJson(objectMapper.writeValueAsString(sanitizedNode), maxLength);
        } catch (RuntimeException | JsonProcessingException | StackOverflowError ex) {
            return UNSERIALIZABLE_JSON;
        }
    }

    public String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return ".".repeat(maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private JsonNode sanitizeNode(JsonNode node, Set<String> sensitiveFields) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<String> fieldNames = objectNode.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (isSensitiveField(fieldName, sensitiveFields)) {
                    objectNode.set(fieldName, TextNode.valueOf(MASK_VALUE));
                } else {
                    objectNode.set(fieldName, sanitizeNode(objectNode.get(fieldName), sensitiveFields));
                }
            }
            return objectNode;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                arrayNode.set(i, sanitizeNode(arrayNode.get(i), sensitiveFields));
            }
            return arrayNode;
        }
        return node;
    }

    private Set<String> sensitiveFields(String[] extraSensitiveFields) {
        Set<String> sensitiveFields = new HashSet<>(DEFAULT_SENSITIVE_FIELDS);
        if (extraSensitiveFields != null) {
            Arrays.stream(extraSensitiveFields)
                    .filter(field -> field != null && !field.isBlank())
                    .map(this::normalize)
                    .forEach(sensitiveFields::add);
        }
        return sensitiveFields;
    }

    private String normalize(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT).replaceAll("[_\\-\\s]", "");
    }

    private boolean isSensitiveField(String fieldName, Set<String> sensitiveFields) {
        String normalizedFieldName = normalize(fieldName);
        if (sensitiveFields.contains(normalizedFieldName)) {
            return true;
        }
        return SENSITIVE_FIELD_KEYWORDS.stream().anyMatch(normalizedFieldName::contains);
    }

    private String truncateJson(String json, int maxLength) throws JsonProcessingException {
        if (json == null || maxLength <= 0 || json.length() <= maxLength) {
            return json;
        }

        String fallback = truncatedJsonString(maxLength);
        String minimalObject = truncatedJsonObject("");
        if (minimalObject.length() > maxLength) {
            return fallback;
        }

        String best = minimalObject;
        int low = 0;
        int high = json.length();
        while (low <= high) {
            int mid = (low + high) / 2;
            String candidate = truncatedJsonObject(json.substring(0, mid));
            if (candidate.length() <= maxLength) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    private String truncatedJsonObject(String content) throws JsonProcessingException {
        ObjectNode truncatedNode = objectMapper.createObjectNode();
        truncatedNode.put("truncated", true);
        truncatedNode.put("content", content + "...");
        return objectMapper.writeValueAsString(truncatedNode);
    }

    private String truncatedJsonString(int maxLength) {
        if (maxLength <= 0) {
            return "";
        }
        if (maxLength == 1) {
            return "0";
        }
        int dotCount = Math.max(0, maxLength - 2);
        return "\"" + ".".repeat(dotCount) + "\"";
    }

    private ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .registerModule(javaTimeFallbackModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private SimpleModule javaTimeFallbackModule() {
        JsonSerializer<TemporalAccessor> serializer = new JsonSerializer<>() {
            @Override
            public void serialize(TemporalAccessor value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        };

        SimpleModule module = new SimpleModule();
        module.addSerializer(Instant.class, serializer);
        module.addSerializer(LocalDate.class, serializer);
        module.addSerializer(LocalDateTime.class, serializer);
        module.addSerializer(LocalTime.class, serializer);
        module.addSerializer(OffsetDateTime.class, serializer);
        module.addSerializer(ZonedDateTime.class, serializer);
        return module;
    }
}
