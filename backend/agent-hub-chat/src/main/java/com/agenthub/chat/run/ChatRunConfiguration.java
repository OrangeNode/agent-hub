package com.agenthub.chat.run;

import com.agenthub.sse.SseEventSender;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatRunProperties.class)
public class ChatRunConfiguration {

    @Bean
    public SseEventSender sseEventSender() {
        return new SseEventSender();
    }

    /**
     * 生成任务使用虚拟线程；并发上限由 {@link ChatRunRegistry} 控制，而不是线程池大小。
     * 不注册为 Executor Bean，避免顶替 Spring Boot 默认的 applicationTaskExecutor。
     */
    public static Executor virtualThreadExecutor() {
        ThreadFactory threadFactory = Thread.ofVirtual().name("chat-run-", 0).factory();
        return command -> threadFactory.newThread(command).start();
    }
}
