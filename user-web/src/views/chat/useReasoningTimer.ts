import { ref } from 'vue'
import type { Ref } from 'vue'

import type { ChatMessageItem } from './chat-types'

/**
 * 思考计时：存在进行中的推理时每秒刷新一次当前时间，全部结束后停止定时器。
 */
export function useReasoningTimer(currentMessages: Ref<ChatMessageItem[]>) {
  const reasoningNow = ref(Date.now())
  let reasoningTimer: ReturnType<typeof window.setInterval> | null = null

  function startReasoningTiming(messageItem: ChatMessageItem) {
    if (!messageItem.reasoningStartedAt) {
      const now = Date.now()
      messageItem.reasoningStartedAt = now
      reasoningNow.value = now
      syncReasoningTimer()
    }
  }

  function finishReasoningTiming(messageItem?: ChatMessageItem) {
    if (!messageItem?.reasoningStartedAt || messageItem.reasoningFinishedAt) {
      return
    }

    const now = Date.now()
    messageItem.reasoningFinishedAt = now
    reasoningNow.value = now
    syncReasoningTimer()
  }

  function syncReasoningTimer() {
    const hasActiveReasoning = currentMessages.value.some(
      (item) => item.reasoningStartedAt && !item.reasoningFinishedAt
    )

    if (hasActiveReasoning && !reasoningTimer) {
      reasoningTimer = window.setInterval(() => {
        reasoningNow.value = Date.now()
      }, 1000)
      return
    }

    if (!hasActiveReasoning && reasoningTimer) {
      window.clearInterval(reasoningTimer)
      reasoningTimer = null
    }
  }

  function disposeReasoningTimer() {
    if (reasoningTimer) {
      window.clearInterval(reasoningTimer)
      reasoningTimer = null
    }
  }

  return {
    reasoningNow,
    startReasoningTiming,
    finishReasoningTiming,
    syncReasoningTimer,
    disposeReasoningTimer
  }
}
