package com.agenthub.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void successShouldUseZeroCodeAndDefaultMessage() {
        ApiResponse<String> response = ApiResponse.success("ok-data");

        assertThat(response.code()).isZero();
        assertThat(response.message()).isEqualTo("ok");
        assertThat(response.data()).isEqualTo("ok-data");
    }

    @Test
    void failureShouldUseErrorCodeMessage() {
        ApiResponse<Void> response = ApiResponse.failure(ErrorCode.UNAUTHORIZED);

        assertThat(response.code()).isEqualTo(401);
        assertThat(response.message()).isEqualTo("未登录或登录已过期");
        assertThat(response.data()).isNull();
    }
}
