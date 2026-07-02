package com.agenthub.server.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class MigrationTestSupport {

    private static final String CONSOLIDATED_SCHEMA_RESOURCE = "/db/migration/V1__init_schema.sql";

    private MigrationTestSupport() {
    }

    public static String readConsolidatedSchema() throws IOException {
        return readClasspathResource(CONSOLIDATED_SCHEMA_RESOURCE);
    }

    private static String readClasspathResource(String resourcePath) throws IOException {
        var resourceUrl = MigrationTestSupport.class.getResource(resourcePath);
        Objects.requireNonNull(resourceUrl, "缺少迁移脚本资源: " + resourcePath);
        try (var inputStream = resourceUrl.openStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
