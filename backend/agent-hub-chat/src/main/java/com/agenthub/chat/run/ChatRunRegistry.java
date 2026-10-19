package com.agenthub.chat.run;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 记录本实例上正在运行的生成任务，并限制并发数量。
 */
@Component
public class ChatRunRegistry {

    private final ChatRunProperties properties;
    private final Clock clock;
    private final Semaphore permits;
    private final Map<Long, ChatRunHandle> activeRuns = new ConcurrentHashMap<>();

    @Autowired
    public ChatRunRegistry(ChatRunProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public ChatRunRegistry(ChatRunProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.permits = new Semaphore(Math.max(1, properties.getMaxConcurrentRuns()));
    }

    /**
     * 在落库任何数据之前占用运行名额；名额耗尽时直接拒绝，避免无界创建线程拖垮实例。
     */
    public ChatRunHandle reserve() {
        if (!permits.tryAcquire()) {
            throw new BusinessException(ErrorCode.RUN_CAPACITY_EXCEEDED);
        }
        return new ChatRunHandle(clock, properties.getMaxDuration(), properties.getStopPollInterval());
    }

    /**
     * 助手消息落库后绑定消息 ID，此后中止请求才能定位到该运行。
     */
    public void activate(ChatRunHandle handle, Long messageId) {
        handle.bind(messageId);
        activeRuns.put(messageId, handle);
    }

    public void release(ChatRunHandle handle) {
        if (handle == null) {
            return;
        }
        if (handle.messageId() != null) {
            activeRuns.remove(handle.messageId(), handle);
        }
        if (handle.releasePermitOnce()) {
            permits.release();
        }
        handle.markReleased();
    }

    public boolean cancel(Long messageId, RunCancelReason reason) {
        ChatRunHandle handle = activeRuns.get(messageId);
        return handle != null && handle.cancel(reason);
    }

    public boolean isActive(Long messageId) {
        return activeRuns.containsKey(messageId);
    }

    public List<ChatRunHandle> activeRuns() {
        return List.copyOf(activeRuns.values());
    }

    public int availablePermits() {
        return permits.availablePermits();
    }
}
