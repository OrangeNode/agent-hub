package com.agenthub.system.role;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryRoleServiceTest {

    @Test
    void shouldCreateUpdateAndDeleteRole() {
        RoleService roleService = new InMemoryRoleService();

        RoleItem created = roleService.create(new CreateRoleCommand(
                "AUDITOR",
                "审计员",
                true,
                List.of(1L)
        ));

        assertThat(created.code()).isEqualTo("AUDITOR");
        assertThat(created.menuIds()).containsExactly(1L);

        RoleItem updated = roleService.update(created.roleId(), new UpdateRoleCommand(
                "审计主管",
                false,
                List.of(1L, 2L)
        ));

        assertThat(updated.name()).isEqualTo("审计主管");
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.menuIds()).containsExactly(1L, 2L);

        roleService.delete(created.roleId());

        assertThat(roleService.list()).extracting(RoleItem::code).doesNotContain("AUDITOR");
    }
}
