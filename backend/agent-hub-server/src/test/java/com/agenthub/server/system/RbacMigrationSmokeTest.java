package com.agenthub.server.system;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RbacMigrationSmokeTest {

    @Test
    void shouldContainInitialRbacMigrationScript() throws IOException {
        var migrationScriptUrl = RbacMigrationSmokeTest.class.getResource("/db/migration/V1__init_rbac.sql");

        assertNotNull(migrationScriptUrl, "应存在 RBAC 初始化迁移脚本");

        String scriptContent;
        try (var inputStream = migrationScriptUrl.openStream()) {
            scriptContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(scriptContent.contains("CREATE TABLE sys_user"), "脚本应创建 sys_user 表");
        assertTrue(scriptContent.contains("CREATE TABLE sys_role_menu"), "脚本应创建 sys_role_menu 表");
        assertTrue(scriptContent.contains("INSERT INTO sys_role"), "脚本应初始化角色数据");
        assertTrue(scriptContent.contains("INSERT INTO sys_menu"), "脚本应初始化菜单数据");
    }
}
