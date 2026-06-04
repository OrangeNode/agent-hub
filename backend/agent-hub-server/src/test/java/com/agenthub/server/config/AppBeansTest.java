package com.agenthub.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.chat.summary.mapper.ChatConversationSummaryMapper;
import com.agenthub.storage.attachment.mapper.ChatAttachmentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app_beans;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false",
        "agent-hub.storage.endpoint=http://127.0.0.1:9000",
        "agent-hub.storage.public-endpoint=http://127.0.0.1:9000",
        "agent-hub.storage.access-key=agenthub",
        "agent-hub.storage.secret-key=agenthub123",
        "agent-hub.storage.bucket=agent-hub-chat-images"
})
class AppBeansTest {

    @Autowired(required = false)
    private ChatAttachmentMapper chatAttachmentMapper;

    @Autowired(required = false)
    private ChatConversationSummaryMapper chatConversationSummaryMapper;

    @Test
    void shouldProvidePasswordEncoderBean() {
        AppBeans appBeans = new AppBeans();

        PasswordEncoder passwordEncoder = appBeans.passwordEncoder();

        assertThat(passwordEncoder).isNotNull();
    }

    @Test
    void shouldProvideMultimodalMapperBeans() {
        assertThat(chatAttachmentMapper).isNotNull();
        assertThat(chatConversationSummaryMapper).isNotNull();
    }
}
