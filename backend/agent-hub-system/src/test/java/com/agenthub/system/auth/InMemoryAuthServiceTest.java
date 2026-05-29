package com.agenthub.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class InMemoryAuthServiceTest {

    @Test
    void shouldLoginWithBootstrapAdmin() {
        AuthService authService = new InMemoryAuthService();

        LoginResult result = authService.login(new LoginCommand("admin", "admin123"));

        assertThat(result.profile().username()).isEqualTo("admin");
        assertThat(result.profile().roles()).contains("ADMIN");
        assertThat(result.profile().permissions()).contains("system:user:list", "agent:manage");
    }

    @Test
    void shouldRejectWrongPassword() {
        AuthService authService = new InMemoryAuthService();

        assertThatThrownBy(() -> authService.login(new LoginCommand("admin", "bad")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }
}
