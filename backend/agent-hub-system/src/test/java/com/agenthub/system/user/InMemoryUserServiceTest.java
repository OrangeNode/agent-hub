package com.agenthub.system.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryUserServiceTest {

    @Test
    void shouldCreateUpdateAndDeleteUser() {
        UserService userService = new InMemoryUserService();

        UserProfile created = userService.create(new CreateUserCommand(
                "operator",
                "运营专员",
                "operator123",
                true,
                List.of("ADMIN")
        ));

        assertThat(created.userId()).isNotNull();
        assertThat(created.username()).isEqualTo("operator");
        assertThat(created.roles()).containsExactly("ADMIN");
        assertThat(userService.list()).extracting(UserProfile::username).contains("operator");

        UserProfile updated = userService.update(created.userId(), new UpdateUserCommand(
                "运营主管",
                false,
                List.of("USER")
        ));

        assertThat(updated.displayName()).isEqualTo("运营主管");
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.roles()).containsExactly("USER");

        userService.delete(created.userId());

        assertThat(userService.list()).extracting(UserProfile::username).doesNotContain("operator");
    }
}
