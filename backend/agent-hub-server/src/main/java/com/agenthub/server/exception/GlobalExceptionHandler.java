package com.agenthub.server.exception;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.common.exception.ValidationError;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常，沿用业务异常携带的错误码和消息。
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException exception) {
        return ApiResponse.failure(exception.errorCode().code(), exception.getMessage());
    }

    /**
     * 处理请求体参数校验异常，返回字段级错误明细。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<List<ValidationError>> handleValidationException(MethodArgumentNotValidException exception) {
        List<ValidationError> errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toValidationError)
                .toList();

        return new ApiResponse<>(
                ErrorCode.BAD_REQUEST.code(),
                "请求参数不正确",
                errors
        );
    }

    /**
     * 处理 Spring Security 未认证异常。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.failure(ErrorCode.UNAUTHORIZED));
    }

    /**
     * 处理 Spring Security 已认证但无权限异常。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.failure(ErrorCode.FORBIDDEN));
    }

    /**
     * SSE 客户端断开或响应流不可写时，不能再按普通 JSON 响应写入内容。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncRequestNotUsableException(AsyncRequestNotUsableException exception) {
        log.warn("SSE 响应已不可写：{}", exception.getMessage());
        return ResponseEntity.noContent().build();
    }

    /**
     * 静态资源或错误 API 路径不存在时，返回统一的资源不存在响应。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException exception) {
        log.warn("请求资源不存在：method={}, path={}", exception.getHttpMethod(), exception.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure(ErrorCode.NOT_FOUND));
    }

    /**
     * 兜底处理未预期异常，并隐藏内部错误细节。
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnknownException(Exception exception) {
        log.error("未处理异常", exception);
        return ApiResponse.failure(ErrorCode.INTERNAL_ERROR);
    }

    /**
     * 将 Spring 字段校验错误转换为统一响应模型。
     */
    private ValidationError toValidationError(FieldError fieldError) {
        return new ValidationError(fieldError.getField(), fieldError.getDefaultMessage());
    }
}
