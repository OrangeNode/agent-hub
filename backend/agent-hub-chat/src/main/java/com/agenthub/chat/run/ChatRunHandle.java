package com.agenthub.chat.run;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次运行的取消令牌。取消是协作式的：生成线程在 chunk 回调、工具输出回调和循环边界检查令牌。
 */
public final class ChatRunHandle {

    private final Clock clock;
    private final Instant deadline;
    private final Duration stopPollInterval;
    private final AtomicReference<RunCancelReason> cancelReason = new AtomicReference<>();
    private final AtomicBoolean permitReleased = new AtomicBoolean(false);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile Long messageId;
    private volatile Instant lastStopPoll;

    ChatRunHandle(Clock clock, Duration maxDuration, Duration stopPollInterval) {
        this.clock = clock;
        Instant now = clock.instant();
        this.deadline = now.plus(maxDuration);
        this.stopPollInterval = stopPollInterval;
        this.lastStopPoll = now;
    }

    public Long messageId() {
        return messageId;
    }

    /**
     * 只记录第一个取消原因，避免断线等后续信号覆盖用户中止或超时。
     */
    public boolean cancel(RunCancelReason reason) {
        return cancelReason.compareAndSet(null, reason);
    }

    public boolean isCancelled() {
        return cancelReason.get() != null;
    }

    public RunCancelReason cancelReason() {
        return cancelReason.get();
    }

    public boolean isExpired() {
        return !clock.instant().isBefore(deadline);
    }

    /**
     * 到达轮询间隔时返回 true 并重置计时，调用方据此决定是否查询数据库中止状态。
     */
    public boolean shouldPollStop() {
        Instant now = clock.instant();
        if (now.isBefore(lastStopPoll.plus(stopPollInterval))) {
            return false;
        }
        lastStopPoll = now;
        return true;
    }

    void bind(Long messageId) {
        this.messageId = messageId;
    }

    boolean releasePermitOnce() {
        return permitReleased.compareAndSet(false, true);
    }

    void markReleased() {
        released.countDown();
    }

    boolean awaitReleased(Duration timeout) throws InterruptedException {
        return released.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
