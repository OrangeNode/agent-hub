package com.agenthub.chat.message.support;

import com.agenthub.chat.message.dto.MessageProcessEvent;
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;

/**
 * 消息过程事件的 JSON 编解码；读取历史数据时容忍字段缺失和未知事件类型。
 */
public final class ProcessEventCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<MessageProcessEvent>> EVENT_LIST_TYPE = new TypeReference<>() {
    };

    private ProcessEventCodec() {
    }

    public static String serialize(List<MessageProcessEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(events);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "消息过程事件序列化失败");
        }
    }

    public static List<MessageProcessEvent> deserialize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<MessageProcessEvent> events = OBJECT_MAPPER.readValue(value, EVENT_LIST_TYPE);
            if (events == null) {
                return List.of();
            }
            return events.stream()
                    .filter(Objects::nonNull)
                    .map(ProcessEventCodec::normalize)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private static MessageProcessEvent normalize(MessageProcessEvent event) {
        if ("reasoning".equals(event.type())) {
            return new MessageProcessEvent("reasoning", defaultString(event.content()), null);
        }
        if ("task_plan_revised".equals(event.type()) && event.task() != null) {
            MessageProcessEvent.TaskProcess task = event.task();
            List<MessageProcessEvent.TaskStepProcess> steps = task.steps() == null ? List.of() : task.steps().stream()
                    .filter(Objects::nonNull)
                    .map(step -> new MessageProcessEvent.TaskStepProcess(
                            step.stepIndex(),
                            defaultString(step.name()),
                            defaultString(step.status()),
                            defaultString(step.errorMessage())
                    ))
                    .toList();
            return new MessageProcessEvent("task_plan_revised", null, null, new MessageProcessEvent.TaskProcess(
                    task.taskRunId(),
                    defaultString(task.mode()),
                    defaultString(task.status()),
                    defaultString(task.title()),
                    defaultString(task.planSummary()),
                    task.currentStepIndex(),
                    task.totalSteps(),
                    task.revision(),
                    defaultString(task.errorMessage()),
                    steps
            ));
        }
        if (!"tool".equals(event.type()) || event.tool() == null) {
            return null;
        }
        MessageProcessEvent.ToolProcess tool = event.tool();
        return new MessageProcessEvent("tool", null, new MessageProcessEvent.ToolProcess(
                defaultString(tool.toolCallId()),
                defaultString(tool.name()).isBlank() ? "工具调用" : tool.name(),
                defaultString(tool.status()).isBlank() ? "running" : tool.status(),
                defaultString(tool.stdout()),
                defaultString(tool.stderr()),
                defaultString(tool.result())
        ));
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
