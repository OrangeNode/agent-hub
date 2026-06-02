package com.agenthub.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void shouldBuildSuccessResponseWithData() {
        ApiResponse<String> response = ApiResponse.success("ok");

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.message()).isEqualTo("成功");
        assertThat(response.data()).isEqualTo("ok");
    }

    @Test
    void shouldBuildFailureResponse() {
        ApiResponse<Void> response = ApiResponse.failure(ErrorCode.UNAUTHORIZED);

        assertThat(response.code()).isEqualTo(401);
        assertThat(response.message()).isEqualTo("未登录或登录已过期");
        assertThat(response.data()).isNull();
    }
}
