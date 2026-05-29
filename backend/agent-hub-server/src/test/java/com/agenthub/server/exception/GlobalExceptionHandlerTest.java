package com.agenthub.server.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import tools.jackson.databind.json.JsonMapper;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder().build()))
                .build();
    }

    @Test
    void shouldConvertBusinessExceptionToUnifiedResponse() throws Exception {
        mockMvc.perform(get("/test/business-error"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数不正确"));
    }

    @Controller
    static class TestController {
        @GetMapping("/test/business-error")
        @ResponseBody
        String businessError() {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求参数不正确");
        }
    }
}
