package com.agenthub.server.system;

import static com.agenthub.server.support.MigrationTestSupport.readConsolidatedSchema;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class RbacMigrationSmokeTest {

    @Test
    void shouldContainInitialRbacSchemaInConsolidatedMigration() throws IOException {
        String scriptContent = readConsolidatedSchema();

        assertTrue(scriptContent.contains("CREATE TABLE sys_user"), "脚本应创建 sys_user 表");
        assertTrue(scriptContent.contains("CREATE TABLE sys_role_menu"), "脚本应创建 sys_role_menu 表");
        assertTrue(scriptContent.contains("INSERT INTO sys_role"), "脚本应初始化角色数据");
        assertTrue(scriptContent.contains("INSERT INTO sys_menu"), "脚本应初始化菜单数据");
    }
}
