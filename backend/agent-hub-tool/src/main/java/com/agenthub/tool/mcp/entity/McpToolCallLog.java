package com.agenthub.tool.mcp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("mcp_tool_call_log")
public class McpToolCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long serverId;
    private Long toolId;
    private Long conversationId;
    private Long userId;
    private Long messageId;
    private String toolCallId;
    private String exposedToolName;
    private String remoteToolName;
    private String argumentsExcerpt;
    private String resultExcerpt;
    private String status;
    private String errorMessage;
    private Long durationMs;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public Long getToolId() {
        return toolId;
    }

    public void setToolId(Long toolId) {
        this.toolId = toolId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getExposedToolName() {
        return exposedToolName;
    }

    public void setExposedToolName(String exposedToolName) {
        this.exposedToolName = exposedToolName;
    }

    public String getRemoteToolName() {
        return remoteToolName;
    }

    public void setRemoteToolName(String remoteToolName) {
        this.remoteToolName = remoteToolName;
    }

    public String getArgumentsExcerpt() {
        return argumentsExcerpt;
    }

    public void setArgumentsExcerpt(String argumentsExcerpt) {
        this.argumentsExcerpt = argumentsExcerpt;
    }

    public String getResultExcerpt() {
        return resultExcerpt;
    }

    public void setResultExcerpt(String resultExcerpt) {
        this.resultExcerpt = resultExcerpt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
