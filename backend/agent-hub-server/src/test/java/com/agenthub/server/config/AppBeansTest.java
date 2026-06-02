package com.agenthub.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AppBeansTest {

    @Test
    void shouldProvidePasswordEncoderBean() {
        AppBeans appBeans = new AppBeans();

        PasswordEncoder passwordEncoder = appBeans.passwordEncoder();

        assertThat(passwordEncoder).isNotNull();
    }
}
