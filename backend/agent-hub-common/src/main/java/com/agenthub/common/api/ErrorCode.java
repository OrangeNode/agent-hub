package com.agenthub.common.api;

public enum ErrorCode {
    SUCCESS(200, "成功"),
    BAD_REQUEST(400, "请求参数不正确"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有访问权限"),
    NOT_FOUND(404, "资源不存在"),
    SESSION_NOT_FOUND(460, "沙箱会话不存在或已过期"),
    SESSION_CAPACITY_EXCEEDED(461, "当前沙箱会话数量已达到上限"),
    VM_START_FAILED(462, "沙箱虚拟机启动失败"),
    COMMAND_DENIED(463, "命令未通过安全校验"),
    COMMAND_TIMEOUT(464, "命令执行超时"),
    COMMAND_FAILED(465, "命令执行失败"),
    FILE_TOO_LARGE(466, "文件大小超过限制"),
    PATH_DENIED(467, "路径不在允许访问范围内"),
    ARTIFACT_SYNC_FAILED(468, "产物同步失败"),
    TOOL_CALL_INVALID(469, "模型返回的工具调用参数无效"),
    TOOL_LOOP_LIMIT_EXCEEDED(470, "工具调用轮次超过上限"),
    RUN_CAPACITY_EXCEEDED(471, "当前生成任务过多，请稍后重试"),
    INTERNAL_ERROR(500, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
