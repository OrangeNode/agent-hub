package com.agenthub.chat.run;

import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.support.AssistantMessageStore;
import com.agenthub.chat.task.runtime.ChatTaskProgressTracker;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 兜底回收不会再有生成线程负责的 STREAMING 消息。
 * <ul>
 *   <li>正常停机：通知本实例所有运行终止，等待它们自行落库，超时仍未结束的直接标记失败。</li>
 *   <li>进程崩溃：定时任务按创建时间回收；任何运行都受最长时间约束，超过“最长时间 + 宽限期”的消息必然无人负责，
 *   因此多实例部署时也不会误伤其他实例上的运行。</li>
 * </ul>
 */
@Component
public class ChatRunMaintenance {

    private static final Logger log = LoggerFactory.getLogger(ChatRunMaintenance.class);
    static final String ORPHANED_MESSAGE = "生成已中断：服务异常退出或运行超时";

    private final ChatRunRegistry registry;
    private final AssistantMessageStore messageStore;
    private final ChatTaskProgressTracker taskTracker;
    private final ChatRunProperties properties;

    public ChatRunMaintenance(
            ChatRunRegistry registry,
            AssistantMessageStore messageStore,
            ChatTaskProgressTracker taskTracker,
            ChatRunProperties properties
    ) {
        this.registry = registry;
        this.messageStore = messageStore;
        this.taskTracker = taskTracker;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 30_000L, fixedDelay = 60_000L)
    public void sweepOrphanedRuns() {
        try {
            int recovered = recoverOrphanedRuns(OffsetDateTime.now()
                    .minus(properties.getMaxDuration())
                    .minus(properties.getOrphanGracePeriod()));
            if (recovered > 0) {
                log.warn("已回收 {} 条无人负责的生成中消息", recovered);
            }
        } catch (RuntimeException exception) {
            log.warn("回收生成中消息失败", exception);
        }
    }

    int recoverOrphanedRuns(OffsetDateTime cutoff) {
        List<ChatMessage> staleMessages = messageStore.findStaleStreaming(cutoff);
        int recovered = 0;
        for (ChatMessage message : staleMessages) {
            if (registry.isActive(message.getId())) {
                continue;
            }
            if (messageStore.failIfStreaming(message.getId(), ORPHANED_MESSAGE)) {
                taskTracker.stopQuietly(message.getUserId(), message.getId());
                recovered++;
            }
        }
        return recovered;
    }

    @PreDestroy
    public void drainOnShutdown() {
        List<ChatRunHandle> runs = registry.activeRuns();
        if (runs.isEmpty()) {
            return;
        }
        log.info("停机前终止 {} 个运行中的生成任务", runs.size());
        runs.forEach(handle -> handle.cancel(RunCancelReason.SHUTDOWN));
        long deadline = System.nanoTime() + properties.getShutdownDrainTimeout().toNanos();
        for (ChatRunHandle handle : runs) {
            try {
                long remainingNanos = Math.max(0, deadline - System.nanoTime());
                if (!handle.awaitReleased(Duration.ofNanos(remainingNanos))) {
                    messageStore.failIfStreaming(handle.messageId(), RunCancelReason.SHUTDOWN.message());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                messageStore.failIfStreaming(handle.messageId(), RunCancelReason.SHUTDOWN.message());
            } catch (RuntimeException exception) {
                log.warn("停机时标记生成任务失败出错: messageId={}", handle.messageId(), exception);
            }
        }
    }
}
