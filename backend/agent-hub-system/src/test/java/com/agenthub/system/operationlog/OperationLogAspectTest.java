package com.agenthub.system.operationlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.system.operationlog.annotation.OperationLog;
import com.agenthub.system.operationlog.aspect.OperationLogAspect;
import com.agenthub.system.operationlog.command.OperationLogQuery;
import com.agenthub.system.operationlog.dto.OperationLogItem;
import com.agenthub.system.operationlog.entity.OperationLogRecord;
import com.agenthub.system.operationlog.service.OperationLogService;
import com.agenthub.system.operationlog.support.OperationLogSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringJUnitConfig(classes = OperationLogAspectTest.TestConfig.class)
class OperationLogAspectTest {

    @Autowired
    private TestOperationService testOperationService;

    @Autowired
    private CapturingOperationLogService operationLogService;

    @BeforeEach
    void setUp() {
        operationLogService.clear();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(7L, "admin", List.of("ADMIN")),
                "token"
        ));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test/operations");
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("User-Agent", "JUnit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRecordSuccessfulOperationWithSanitizedParamsAndResult() {
        Map<String, Object> result = testOperationService.success("vm-1", "plain-password");

        assertThat(result).containsEntry("ok", true);
        assertThat(AopProxyUtils.ultimateTargetClass(testOperationService)).isEqualTo(TestOperationService.class);

        OperationLogRecord record = operationLogService.onlyRecord();
        assertThat(record.getUserId()).isEqualTo(7L);
        assertThat(record.getUsername()).isEqualTo("admin");
        assertThat(record.getModule()).isEqualTo("测试模块");
        assertThat(record.getAction()).isEqualTo("成功操作");
        assertThat(record.getTargetType()).isEqualTo("VM");
        assertThat(record.getTargetId()).isEqualTo("vm-1");
        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getRequestMethod()).isEqualTo("POST");
        assertThat(record.getRequestUri()).isEqualTo("/api/test/operations");
        assertThat(record.getClientIp()).isEqualTo("10.0.0.8");
        assertThat(record.getUserAgent()).isEqualTo("JUnit");
        assertThat(record.getDurationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(record.getParamsJson()).contains("\"targetId\":\"vm-1\"");
        assertThat(record.getParamsJson()).contains("\"password\":\"******\"");
        assertThat(record.getParamsJson()).doesNotContain("plain-password");
        assertThat(record.getResultJson()).contains("\"token\":\"******\"");
        assertThat(record.getResultJson()).doesNotContain("secret-token");
    }

    @Test
    void shouldRecordFailedOperationAndRethrowBusinessException() {
        assertThatThrownBy(() -> testOperationService.failure("vm-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("失败原因");

        OperationLogRecord record = operationLogService.onlyRecord();
        assertThat(record.getModule()).isEqualTo("测试模块");
        assertThat(record.getAction()).isEqualTo("失败操作");
        assertThat(record.getTargetId()).isEqualTo("vm-2");
        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getErrorCode()).isEqualTo(String.valueOf(ErrorCode.BAD_REQUEST.code()));
        assertThat(record.getErrorMessage()).isEqualTo("失败原因");
    }

    @Test
    void shouldRecordRuntimeExceptionAsInternalError() {
        assertThatThrownBy(() -> testOperationService.runtimeFailure("vm-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("运行时失败");

        OperationLogRecord record = operationLogService.onlyRecord();
        assertThat(record.getTargetId()).isEqualTo("vm-3");
        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getErrorCode()).isEqualTo(String.valueOf(ErrorCode.INTERNAL_ERROR.code()));
        assertThat(record.getErrorMessage()).isEqualTo("运行时失败");
    }

    @Test
    void shouldRejectBlankReasonBeforeInvokingTargetMethod() {
        assertThatThrownBy(() -> testOperationService.requireReason(new ReasonCommand(" ")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("操作原因不能为空");

        assertThat(testOperationService.invoked).isFalse();
        OperationLogRecord record = operationLogService.onlyRecord();
        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getErrorCode()).isEqualTo(String.valueOf(ErrorCode.BAD_REQUEST.code()));
        assertThat(record.getReason()).isNull();
    }

    @Test
    void shouldNotFailSuccessfulOperationWhenOperationLogRecordFails() {
        operationLogService.failOnRecord();

        Map<String, Object> result = testOperationService.success("vm-4", "plain-password");

        assertThat(result).containsEntry("ok", true);
        assertThat(operationLogService.records).isEmpty();
    }

    @Test
    void shouldPreserveOriginalBusinessExceptionWhenOperationLogRecordFails() {
        operationLogService.failOnRecord();

        assertThatThrownBy(() -> testOperationService.failure("vm-5"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("失败原因");

        assertThat(operationLogService.records).isEmpty();
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        OperationLogAspect operationLogAspect(
                OperationLogService operationLogService,
                OperationLogSanitizer operationLogSanitizer
        ) {
            return new OperationLogAspect(operationLogService, operationLogSanitizer);
        }

        @Bean
        OperationLogSanitizer operationLogSanitizer() {
            return new OperationLogSanitizer();
        }

        @Bean
        CapturingOperationLogService operationLogService() {
            return new CapturingOperationLogService();
        }

        @Bean
        TestOperationService testOperationService() {
            return new TestOperationService();
        }
    }

    static class TestOperationService {

        private boolean invoked;

        @OperationLog(module = "测试模块", action = "成功操作", target = "#targetId", targetType = "VM", recordParams = true, recordResult = true)
        public Map<String, Object> success(String targetId, String password) {
            return Map.of("ok", true, "token", "secret-token");
        }

        @OperationLog(module = "测试模块", action = "失败操作", target = "#targetId", targetType = "VM", recordParams = true)
        public void failure(String targetId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "失败原因");
        }

        @OperationLog(module = "测试模块", action = "运行时失败", target = "#targetId", targetType = "VM", recordParams = true)
        public void runtimeFailure(String targetId) {
            throw new IllegalStateException("运行时失败");
        }

        @OperationLog(module = "测试模块", action = "需要原因", requireReason = true, recordParams = true)
        public void requireReason(ReasonCommand command) {
            invoked = true;
        }
    }

    record ReasonCommand(String reason) {
    }

    static class CapturingOperationLogService implements OperationLogService {

        private final List<OperationLogRecord> records = new ArrayList<>();
        private boolean failOnRecord;

        @Override
        public void record(OperationLogRecord record) {
            if (failOnRecord) {
                throw new IllegalStateException("日志入库失败");
            }
            records.add(record);
        }

        @Override
        public List<OperationLogItem> list(OperationLogQuery query) {
            return List.of();
        }

        private void clear() {
            records.clear();
            failOnRecord = false;
        }

        private OperationLogRecord onlyRecord() {
            assertThat(records).hasSize(1);
            return records.getFirst();
        }

        private void failOnRecord() {
            failOnRecord = true;
        }
    }
}
