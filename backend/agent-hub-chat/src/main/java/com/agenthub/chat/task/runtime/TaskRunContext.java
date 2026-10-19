package com.agenthub.chat.task.runtime;

import com.agenthub.chat.task.dto.TaskProgressItem;

/**
 * 一次运行关联的任务进度。进度展示失败后整体降级，不再推送后续进度事件。
 */
public class TaskRunContext {

    private final TaskProgressItem initialProgress;
    private volatile boolean progressDisabled;

    public TaskRunContext(TaskProgressItem initialProgress) {
        this.initialProgress = initialProgress;
    }

    public TaskProgressItem initialProgress() {
        return initialProgress;
    }

    public Long taskRunId() {
        return initialProgress == null ? null : initialProgress.taskRunId();
    }

    public boolean progressDisabled() {
        return progressDisabled;
    }

    public void disableProgress() {
        this.progressDisabled = true;
    }
}
