package com.agenthub.server.support;

import com.agenthub.server.auth.AuthController;
import com.agenthub.server.config.SecurityConfig;
import com.agenthub.server.exception.GlobalExceptionHandler;
import com.agenthub.server.system.SystemController;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        AuthController.class,
        SystemController.class
})
public class ServerWebTestApplication {
}
