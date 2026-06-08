package com.agenthub.sandbox.execution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("sandbox_execution")
public class SandboxExecution {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sandboxSessionId;
    private Long conversationId;
    private Long messageId;
    private String toolCallId;
    private String toolName;
    private String command;
    private String workingDir;
    private Integer timeoutSeconds;
    private Integer exitCode;
    private String status;
    private Integer errorCode;
    private String errorMessage;
    private String stdoutExcerpt;
    private String stderrExcerpt;
    private Long durationMs;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSandboxSessionId() {
        return sandboxSessionId;
    }

    public void setSandboxSessionId(Long sandboxSessionId) {
        this.sandboxSessionId = sandboxSessionId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
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

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getStdoutExcerpt() {
        return stdoutExcerpt;
    }

    public void setStdoutExcerpt(String stdoutExcerpt) {
        this.stdoutExcerpt = stdoutExcerpt;
    }

    public String getStderrExcerpt() {
        return stderrExcerpt;
    }

    public void setStderrExcerpt(String stderrExcerpt) {
        this.stderrExcerpt = stderrExcerpt;
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
