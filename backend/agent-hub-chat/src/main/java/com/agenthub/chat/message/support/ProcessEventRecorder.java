package com.agenthub.chat.message.support;

import com.agenthub.chat.message.dto.MessageProcessEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 记录一次助手回复中的推理片段和工具调用过程，最终随消息一起落库。
 * 工具输出回调可能来自其他线程，因此所有写操作串行化。
 */
public class ProcessEventRecorder {

    private final List<MessageProcessEvent> events = new ArrayList<>();
    private final Map<String, Integer> toolIndexes = new HashMap<>();

    public synchronized List<MessageProcessEvent> events() {
        return List.copyOf(events);
    }

    public synchronized void appendReasoning(String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        if (!events.isEmpty() && "reasoning".equals(events.getLast().type())) {
            MessageProcessEvent last = events.getLast();
            events.set(events.size() - 1,
                    new MessageProcessEvent("reasoning", last.content() + content, null));
            return;
        }
        events.add(new MessageProcessEvent("reasoning", content, null));
    }

    public synchronized void startTool(String toolCallId, String name) {
        updateTool(toolCallId, name, tool -> new MessageProcessEvent.ToolProcess(
                tool.toolCallId(),
                resolveToolName(name, tool.name()),
                "running",
                tool.stdout(),
                tool.stderr(),
                tool.result()
        ));
    }

    public synchronized void appendToolOutput(String toolCallId, String name, String stream, String content) {
        updateTool(toolCallId, name, tool -> new MessageProcessEvent.ToolProcess(
                tool.toolCallId(),
                resolveToolName(name, tool.name()),
                tool.status(),
                "stderr".equals(stream) ? tool.stdout() : tool.stdout() + defaultString(content),
                "stderr".equals(stream) ? tool.stderr() + defaultString(content) : tool.stderr(),
                tool.result()
        ));
    }

    public synchronized void completeTool(String toolCallId, String name, String status, String result) {
        updateTool(toolCallId, name, tool -> new MessageProcessEvent.ToolProcess(
                tool.toolCallId(),
                resolveToolName(name, tool.name()),
                defaultString(status).isBlank() ? "completed" : status,
                tool.stdout(),
                tool.stderr(),
                defaultString(result)
        ));
    }

    private void updateTool(
            String toolCallId,
            String name,
            Function<MessageProcessEvent.ToolProcess, MessageProcessEvent.ToolProcess> updater
    ) {
        String resolvedToolCallId = defaultString(toolCallId);
        Integer index = toolIndexes.get(resolvedToolCallId);
        MessageProcessEvent.ToolProcess current;
        if (index == null) {
            current = new MessageProcessEvent.ToolProcess(
                    resolvedToolCallId,
                    resolveToolName(name, ""),
                    "running",
                    "",
                    "",
                    ""
            );
            events.add(new MessageProcessEvent("tool", null, current));
            index = events.size() - 1;
            toolIndexes.put(resolvedToolCallId, index);
        } else {
            current = events.get(index).tool();
        }
        events.set(index, new MessageProcessEvent("tool", null, updater.apply(current)));
    }

    private static String resolveToolName(String preferred, String fallback) {
        String value = defaultString(preferred);
        if (!value.isBlank()) {
            return value;
        }
        value = defaultString(fallback);
        return value.isBlank() ? "工具调用" : value;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
