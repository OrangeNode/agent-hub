package com.agenthub.server.config;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.security.jwt.JwtProperties;
import com.agenthub.security.jwt.JwtService;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.system.auth.service.AuthService;
import com.agenthub.system.user.dto.UserAccount;
import com.agenthub.system.user.dto.UserProfile;
import com.agenthub.system.user.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {

    @Bean
    JwtProperties jwtProperties(
            @Value("${agent-hub.security.jwt.secret:agent-hub-demo-secret-agent-hub-demo-secret}") String secret,
            @Value("${agent-hub.security.jwt.ttl-hours:12}") long ttlHours
    ) {
        return new JwtProperties(
                secret,
                Duration.ofHours(ttlHours)
        );
    }

    @Bean
    JwtService jwtService(JwtProperties jwtProperties) {
        return new JwtService(jwtProperties);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            AuthService authService,
            UserService userService,
            ObjectMapper objectMapper
    )
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/login", "/error").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN))
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, authService, userService, objectMapper), UsernamePasswordAuthenticationFilter.class)
                .logout(Customizer.withDefaults())
                .build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, int status, ErrorCode errorCode)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(errorCode));
    }

    private static class JwtAuthenticationFilter extends OncePerRequestFilter {

        private final JwtService jwtService;
        private final AuthService authService;
        private final UserService userService;
        private final ObjectMapper objectMapper;

        private JwtAuthenticationFilter(
                JwtService jwtService,
                AuthService authService,
                UserService userService,
                ObjectMapper objectMapper
        ) {
            this.jwtService = jwtService;
            this.authService = authService;
            this.userService = userService;
            this.objectMapper = objectMapper;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            String token = resolveToken(request);

            if (token != null) {
                try {
                    LoginUser loginUser = jwtService.parseToken(token);
                    UserAccount userAccount = userService.findAccountById(loginUser.userId())
                            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
                    UserProfile profile = authService.currentUser(userAccount.username());
                    LoginUser resolvedLoginUser = new LoginUser(
                            profile.userId(),
                            profile.username(),
                            profile.roles()
                    );
                    Collection<? extends GrantedAuthority> authorities = Stream.concat(
                                    profile.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)),
                                    profile.permissions().stream().map(SimpleGrantedAuthority::new)
                            )
                            .toList();
                    UsernamePasswordAuthenticationToken authenticationToken =
                            new UsernamePasswordAuthenticationToken(resolvedLoginUser, token, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                } catch (BusinessException exception) {
                    int status = exception.errorCode() == ErrorCode.FORBIDDEN
                            ? HttpServletResponse.SC_FORBIDDEN
                            : HttpServletResponse.SC_UNAUTHORIZED;
                    ErrorCode errorCode = exception.errorCode() == ErrorCode.FORBIDDEN
                            ? ErrorCode.FORBIDDEN
                            : ErrorCode.UNAUTHORIZED;
                    writeError(response, objectMapper, status, errorCode);
                    return;
                }
            }

            filterChain.doFilter(request, response);
        }

        private String resolveToken(HttpServletRequest request) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                return token.isBlank() ? null : token;
            }

            if ("/api/chat/stream".equals(request.getRequestURI())) {
                String token = request.getParameter("token");
                return token == null || token.isBlank() ? null : token;
            }

            return null;
        }
    }
}
