package com.agenthub.system.operationlog.aspect;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.security.principal.LoginUser;
import com.agenthub.system.operationlog.annotation.OperationLog;
import com.agenthub.system.operationlog.entity.OperationLogRecord;
import com.agenthub.system.operationlog.service.OperationLogService;
import com.agenthub.system.operationlog.support.OperationLogSanitizer;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class OperationLogAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationLogAspect.class);
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final OperationLogService operationLogService;
    private final OperationLogSanitizer operationLogSanitizer;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public OperationLogAspect(OperationLogService operationLogService, OperationLogSanitizer operationLogSanitizer) {
        this.operationLogService = operationLogService;
        this.operationLogSanitizer = operationLogSanitizer;
    }

    @Around("@annotation(operationLog)")
    public Object recordOperation(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        long startedAt = System.nanoTime();
        OperationLogRecord record = buildBaseRecord(joinPoint, operationLog);
        record.setParamsJson(operationLog.recordParams()
                ? operationLogSanitizer.toSanitizedJson(buildParams(joinPoint), operationLog.maxLength(), operationLog.sensitiveFields())
                : null);

        try {
            String reason = extractReason(joinPoint);
            record.setReason(normalizeBlank(reason));
            if (operationLog.requireReason() && !StringUtils.hasText(reason)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "操作原因不能为空");
            }

            Object result = joinPoint.proceed();
            record.setStatus(STATUS_SUCCESS);
            record.setResultJson(operationLog.recordResult()
                    ? operationLogSanitizer.toSanitizedJson(result, operationLog.maxLength(), operationLog.sensitiveFields())
                    : null);
            record.setDurationMs(elapsedMillis(startedAt));
            safeRecord(record);
            return result;
        } catch (BusinessException exception) {
            recordFailure(record, startedAt, String.valueOf(exception.errorCode().code()), exception.getMessage(), operationLog.maxLength());
            throw exception;
        } catch (RuntimeException exception) {
            recordFailure(record, startedAt, String.valueOf(ErrorCode.INTERNAL_ERROR.code()), exception.getMessage(), operationLog.maxLength());
            throw exception;
        } catch (Throwable throwable) {
            recordFailure(record, startedAt, String.valueOf(ErrorCode.INTERNAL_ERROR.code()), throwable.getMessage(), operationLog.maxLength());
            throw throwable;
        }
    }

    private OperationLogRecord buildBaseRecord(ProceedingJoinPoint joinPoint, OperationLog operationLog) {
        OperationLogRecord record = new OperationLogRecord();
        record.setTraceId(resolveTraceId());
        fillCurrentUser(record);
        record.setModule(operationLog.module());
        record.setAction(operationLog.action());
        record.setTargetType(normalizeBlank(operationLog.targetType()));
        record.setTargetId(resolveTarget(joinPoint, operationLog.target()));
        record.setOperationType(StringUtils.hasText(operationLog.operationType()) ? operationLog.operationType() : "MANAGE");
        record.setCreatedAt(OffsetDateTime.now());
        fillRequestInfo(record);
        return record;
    }

    private void fillCurrentUser(OperationLogRecord record) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof LoginUser loginUser) {
            record.setUserId(loginUser.userId());
            record.setUsername(loginUser.username());
            return;
        }
        if (principal != null && !"anonymousUser".equals(principal)) {
            record.setUsername(String.valueOf(principal));
        }
    }

    private void fillRequestInfo(OperationLogRecord record) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return;
        }
        record.setRequestMethod(request.getMethod());
        record.setRequestUri(request.getRequestURI());
        record.setClientIp(resolveClientIp(request));
        record.setUserAgent(request.getHeader("User-Agent"));
    }

    private String resolveTraceId() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String traceId = request.getHeader("X-Trace-Id");
        if (!StringUtils.hasText(traceId)) {
            traceId = request.getHeader("X-Request-Id");
        }
        return normalizeBlank(traceId);
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveTarget(ProceedingJoinPoint joinPoint, String targetExpression) {
        if (!StringUtils.hasText(targetExpression)) {
            return null;
        }
        if (!targetExpression.startsWith("#")) {
            return targetExpression;
        }

        try {
            Object value = expressionParser.parseExpression(targetExpression).getValue(buildEvaluationContext(joinPoint));
            return value == null ? null : String.valueOf(value);
        } catch (RuntimeException exception) {
            return targetExpression;
        }
    }

    private StandardEvaluationContext buildEvaluationContext(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        String[] parameterNames = parameterNames(joinPoint);
        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
            if (parameterNames != null && i < parameterNames.length && StringUtils.hasText(parameterNames[i])) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        return context;
    }

    private Map<String, Object> buildParams(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        String[] parameterNames = parameterNames(joinPoint);
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (shouldSkipParam(arg)) {
                continue;
            }
            String name = parameterNames != null && i < parameterNames.length && StringUtils.hasText(parameterNames[i])
                    ? parameterNames[i]
                    : "arg" + i;
            params.put(name, arg);
        }
        return params;
    }

    private boolean shouldSkipParam(Object arg) {
        return arg instanceof ServletRequest
                || arg instanceof ServletResponse
                || arg instanceof BindingResult
                || arg instanceof Authentication;
    }

    private String[] parameterNames(ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String[] names = parameterNameDiscoverer.getParameterNames(method);
        if (names != null) {
            return names;
        }
        return ((MethodSignature) joinPoint.getSignature()).getParameterNames();
    }

    private String extractReason(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        String[] parameterNames = parameterNames(joinPoint);
        for (int i = 0; i < args.length; i++) {
            if (parameterNames != null
                    && i < parameterNames.length
                    && "reason".equalsIgnoreCase(parameterNames[i])
                    && args[i] != null) {
                return String.valueOf(args[i]);
            }
        }

        for (Object arg : args) {
            String reason = extractReasonFromArg(arg);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private String extractReasonFromArg(Object arg) {
        if (arg == null) {
            return null;
        }
        if (arg instanceof Map<?, ?> map && map.containsKey("reason")) {
            Object value = map.get("reason");
            return value == null ? null : String.valueOf(value);
        }
        String reason = invokeStringAccessor(arg, "reason");
        if (reason != null) {
            return reason;
        }
        return invokeStringAccessor(arg, "getReason");
    }

    private String invokeStringAccessor(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private void recordFailure(
            OperationLogRecord record,
            long startedAt,
            String errorCode,
            String errorMessage,
            int maxLength
    ) {
        record.setStatus(STATUS_FAILED);
        record.setErrorCode(errorCode);
        record.setErrorMessage(operationLogSanitizer.truncate(errorMessage, maxLength));
        record.setDurationMs(elapsedMillis(startedAt));
        safeRecord(record);
    }

    private void safeRecord(OperationLogRecord record) {
        try {
            operationLogService.record(record);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "记录操作日志失败，module={}, action={}, targetType={}, targetId={}, status={}, errorType={}, error={}",
                    record.getModule(),
                    record.getAction(),
                    record.getTargetType(),
                    record.getTargetId(),
                    record.getStatus(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage()
            );
        }
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private String normalizeBlank(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
