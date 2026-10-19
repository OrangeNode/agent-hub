package com.agenthub.chat.run;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 单次 Agent 运行的资源约束。
 */
@ConfigurationProperties(prefix = "agent-hub.chat.run")
public class ChatRunProperties {

    /**
     * 单次运行最长时间，超时后自动终止并标记失败。
     */
    private Duration maxDuration = Duration.ofMinutes(30);

    /**
     * 单个实例同时运行的生成任务上限，超出时直接拒绝新请求。
     */
    private int maxConcurrentRuns = 200;

    /**
     * 生成线程轮询数据库中止状态的最小间隔，用于感知落在其他实例上的中止请求。
     */
    private Duration stopPollInterval = Duration.ofSeconds(3);

    /**
     * 超过最长运行时间后再等待的宽限期，之后仍为 STREAMING 的消息视为孤儿回收。
     */
    private Duration orphanGracePeriod = Duration.ofMinutes(5);

    /**
     * 停机时等待运行中任务自行收尾的时间，超时后直接标记失败。
     */
    private Duration shutdownDrainTimeout = Duration.ofSeconds(10);

    /**
     * 单次运行的工具调用总次数上限。
     */
    private int maxToolCalls = 100;

    public Duration getMaxDuration() {
        return maxDuration;
    }

    public void setMaxDuration(Duration maxDuration) {
        this.maxDuration = maxDuration;
    }

    public int getMaxConcurrentRuns() {
        return maxConcurrentRuns;
    }

    public void setMaxConcurrentRuns(int maxConcurrentRuns) {
        this.maxConcurrentRuns = maxConcurrentRuns;
    }

    public Duration getStopPollInterval() {
        return stopPollInterval;
    }

    public void setStopPollInterval(Duration stopPollInterval) {
        this.stopPollInterval = stopPollInterval;
    }

    public Duration getOrphanGracePeriod() {
        return orphanGracePeriod;
    }

    public void setOrphanGracePeriod(Duration orphanGracePeriod) {
        this.orphanGracePeriod = orphanGracePeriod;
    }

    public Duration getShutdownDrainTimeout() {
        return shutdownDrainTimeout;
    }

    public void setShutdownDrainTimeout(Duration shutdownDrainTimeout) {
        this.shutdownDrainTimeout = shutdownDrainTimeout;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }
}
