package com.agenthub.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void shouldExposeErrorCodeAndChineseMessage() {
        BusinessException exception = new BusinessException(ErrorCode.BAD_REQUEST, "请求参数不正确");

        assertThat(exception.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(exception.getMessage()).isEqualTo("请求参数不正确");
    }
}
