package com.agenthub.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 固化模块分层：common → 基础能力（ai、tool、storage、agent、system）→ 能力实现（sandbox、skill）→ 应用层（chat）→ server。
 * 跨模块只能调用对方的 service/dto/接口，不能直接访问对方的 mapper 和 entity。
 */
class ModuleBoundaryTest {

    private static final List<String> BUSINESS_MODULES = List.of(
            "system", "ai", "agent", "skill", "sandbox", "tool", "storage", "chat"
    );

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.agenthub");
    }

    @Test
    void modulesShouldBeFreeOfCycles() {
        slices().matching("com.agenthub.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }

    @Test
    void modulesShouldNotAccessOtherModulesPersistenceLayer() {
        for (String module : BUSINESS_MODULES) {
            String modulePackage = "com.agenthub." + module + "..";
            noClasses()
                    .that().resideOutsideOfPackages(modulePackage, "com.agenthub.server..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.agenthub." + module + "..mapper..",
                            "com.agenthub." + module + "..entity..")
                    .because("跨模块访问应通过 " + module + " 模块的 service 接口，避免绕过业务校验")
                    .check(classes);
        }
    }

    @Test
    void capabilityModulesShouldNotDependOnChatApplicationLayer() {
        noClasses()
                .that().resideInAnyPackage("com.agenthub.system..", "com.agenthub.ai..", "com.agenthub.agent..",
                        "com.agenthub.skill..", "com.agenthub.sandbox..", "com.agenthub.tool..",
                        "com.agenthub.storage..", "com.agenthub.sse..", "com.agenthub.security..",
                        "com.agenthub.common..")
                .should().dependOnClassesThat().resideInAPackage("com.agenthub.chat..")
                .check(classes);
    }

    @Test
    void toolSpiShouldNotDependOnConcreteToolImplementations() {
        noClasses()
                .that().resideInAPackage("com.agenthub.tool..")
                .should().dependOnClassesThat().resideInAnyPackage("com.agenthub.sandbox..", "com.agenthub.skill..")
                .because("沙箱、Skill 等能力通过实现 ToolProvider 接入，tool 模块只定义 SPI 和注册表")
                .check(classes);
    }

    @Test
    void commonShouldNotDependOnAnyOtherModule() {
        noClasses()
                .that().resideInAPackage("com.agenthub.common..")
                .should().dependOnClassesThat().resideOutsideOfPackages("com.agenthub.common..", "java..",
                        "javax..", "jakarta..", "org..", "com.fasterxml..", "com.baomidou..")
                .check(classes);
    }
}
