package com.agenthub.server.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数不正确"));
    }

    @Test
    void shouldNotWriteJsonBodyWhenSseResponseCannotBeUsed() throws Exception {
        mockMvc.perform(get("/test/sse-client-abort").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void shouldConvertMissingResourceToNotFoundResponse() throws Exception {
        mockMvc.perform(get("/test/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("资源不存在"));
    }

    @Controller
    static class TestController {
        @GetMapping("/test/business-error")
        @ResponseBody
        String businessError() {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求参数不正确");
        }

        @GetMapping(value = "/test/sse-client-abort", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        @ResponseBody
        String sseClientAbort() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("客户端已断开连接");
        }

        @GetMapping("/test/missing-resource")
        @ResponseBody
        String missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "静态资源", "/api/missing");
        }
    }
}
