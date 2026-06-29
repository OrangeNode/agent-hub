package com.agenthub.chat.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.chat.task.dto.TaskProgressItem;
import com.agenthub.chat.task.dto.TaskStepItem;
import com.agenthub.chat.task.service.AgentTaskService;
import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class ChatTaskControllerTest {

    @Test
    void shouldReturnActiveTaskProgressFromService() {
        AgentTaskService taskService = mock(AgentTaskService.class);
        ChatTaskController controller = new ChatTaskController(taskService);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new LoginUser(7L, "alice", List.of("USER")));
        TaskProgressItem progress = new TaskProgressItem(
                123L,
                "PLAN_EXECUTE",
                "RUNNING",
                "生成报告",
                "分析材料并生成报告",
                1,
                2,
                1,
                null,
                List.of(new TaskStepItem(1, "分析材料", "RUNNING", null))
        );
        when(taskService.findActiveProgress(7L, 100L)).thenReturn(progress);

        ApiResponse<TaskProgressItem> response = controller.activeTask(authentication, 100L);

        assertThat(response.data()).isSameAs(progress);
        verify(taskService).findActiveProgress(7L, 100L);
    }

    @Test
    void shouldReturnLatestTaskProgressFromService() {
        AgentTaskService taskService = mock(AgentTaskService.class);
        ChatTaskController controller = new ChatTaskController(taskService);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new LoginUser(7L, "alice", List.of("USER")));
        TaskProgressItem progress = new TaskProgressItem(
                123L,
                "PLAN_EXECUTE",
                "COMPLETED",
                "task",
                "summary",
                2,
                2,
                1,
                null,
                List.of(new TaskStepItem(1, "collect context", "COMPLETED", null))
        );
        when(taskService.findLatestProgress(7L, 100L)).thenReturn(progress);

        ApiResponse<TaskProgressItem> response = controller.latestTask(authentication, 100L);

        assertThat(response.data()).isSameAs(progress);
        verify(taskService).findLatestProgress(7L, 100L);
    }
}
