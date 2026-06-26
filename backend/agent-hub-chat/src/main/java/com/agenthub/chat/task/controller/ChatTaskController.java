package com.agenthub.chat.task.controller;

import com.agenthub.chat.task.dto.TaskProgressItem;
import com.agenthub.chat.task.service.AgentTaskService;
import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatTaskController {

    private final AgentTaskService taskService;

    public ChatTaskController(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/conversations/{conversationId}/tasks/active")
    public ApiResponse<TaskProgressItem> activeTask(Authentication authentication, @PathVariable Long conversationId) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(taskService.findActiveProgress(loginUser.userId(), conversationId));
    }
}
