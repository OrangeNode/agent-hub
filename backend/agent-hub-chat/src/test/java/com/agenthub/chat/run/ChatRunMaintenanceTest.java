package com.agenthub.chat.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.message.support.AssistantMessageStore;
import com.agenthub.chat.task.runtime.ChatTaskProgressTracker;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRunMaintenanceTest {

    private final AssistantMessageStore messageStore = mock(AssistantMessageStore.class);
    private final ChatTaskProgressTracker taskTracker = mock(ChatTaskProgressTracker.class);
    private final ChatRunProperties properties = new ChatRunProperties();
    private final ChatRunRegistry registry = new ChatRunRegistry(properties);
    private final ChatRunMaintenance maintenance = new ChatRunMaintenance(registry, messageStore, taskTracker, properties);

    @Test
    void shouldFailStaleStreamingMessagesAndStopTheirTaskRuns() {
        OffsetDateTime cutoff = OffsetDateTime.now();
        when(messageStore.findStaleStreaming(cutoff)).thenReturn(List.of(message(11L, 7L), message(12L, 8L)));
        when(messageStore.failIfStreaming(11L, ChatRunMaintenance.ORPHANED_MESSAGE)).thenReturn(true);
        when(messageStore.failIfStreaming(12L, ChatRunMaintenance.ORPHANED_MESSAGE)).thenReturn(false);

        int recovered = maintenance.recoverOrphanedRuns(cutoff);

        assertThat(recovered).isEqualTo(1);
        verify(taskTracker).stopQuietly(7L, 11L);
        verify(taskTracker, never()).stopQuietly(8L, 12L);
    }

    @Test
    void shouldSkipMessagesStillRunningOnThisInstance() {
        OffsetDateTime cutoff = OffsetDateTime.now();
        ChatRunHandle handle = registry.reserve();
        registry.activate(handle, 11L);
        when(messageStore.findStaleStreaming(cutoff)).thenReturn(List.of(message(11L, 7L)));

        int recovered = maintenance.recoverOrphanedRuns(cutoff);

        assertThat(recovered).isZero();
        verify(messageStore, never()).failIfStreaming(any(), any());
    }

    @Test
    void shouldCancelActiveRunsAndFailThoseNotDrainedOnShutdown() {
        properties.setShutdownDrainTimeout(Duration.ofMillis(50));
        ChatRunHandle drained = registry.reserve();
        registry.activate(drained, 21L);
        ChatRunHandle stuck = registry.reserve();
        registry.activate(stuck, 22L);
        Thread.ofVirtual().start(() -> {
            while (!drained.isCancelled()) {
                Thread.onSpinWait();
            }
            registry.release(drained);
        });

        maintenance.drainOnShutdown();

        assertThat(drained.cancelReason()).isEqualTo(RunCancelReason.SHUTDOWN);
        assertThat(stuck.cancelReason()).isEqualTo(RunCancelReason.SHUTDOWN);
        verify(messageStore, never()).failIfStreaming(21L, RunCancelReason.SHUTDOWN.message());
        verify(messageStore).failIfStreaming(22L, RunCancelReason.SHUTDOWN.message());
    }

    private static ChatMessage message(Long id, Long userId) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setUserId(userId);
        message.setRole("ASSISTANT");
        message.setStatus("STREAMING");
        return message;
    }
}
