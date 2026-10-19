import { ref } from 'vue'
import type { Ref } from 'vue'

import { fetchLatestTaskProgress } from '../../api/chat'
import type { ChatTaskProgressRecord } from '../../api/chat'
import { normalizeTaskProgress } from '../chat-task-progress.helpers'
import { markTaskProgressTerminal, shouldApplyTaskProgressForStream } from '../chat-view.helpers'

/**
 * 任务进度面板状态。每次发送生成一个 stream token，只有当前 token 且仍停留在对应会话时才接受进度事件，
 * 防止旧 stream 或已切走的会话覆盖当前面板。
 */
export function useTaskProgress(activeConversationId: Ref<number | null>) {
  const activeTaskProgress = ref<ChatTaskProgressRecord | null>(null)
  const currentTaskStreamToken = ref<number | null>(null)
  const taskStreamConversationIds = ref<number[]>([])
  let taskStreamSequence = 0

  function applyTaskProgress(progress: ChatTaskProgressRecord | null | undefined) {
    activeTaskProgress.value = progress ? normalizeTaskProgress(progress) : null
  }

  function activateTaskStreamContext(conversationId: number) {
    const taskStreamToken = taskStreamSequence + 1
    taskStreamSequence = taskStreamToken
    currentTaskStreamToken.value = taskStreamToken
    taskStreamConversationIds.value = [conversationId]
    return taskStreamToken
  }

  function deactivateTaskStreamContext() {
    currentTaskStreamToken.value = null
    taskStreamConversationIds.value = []
  }

  /**
   * 新会话的临时 ID 会在 stream 中被替换为真实 ID，两者都需要视为同一个 stream 的会话。
   */
  function registerTaskStreamConversationId(conversationId?: number | null, taskStreamToken = currentTaskStreamToken.value) {
    if (
      !conversationId
      || !taskStreamToken
      || currentTaskStreamToken.value !== taskStreamToken
      || taskStreamConversationIds.value.includes(conversationId)
    ) {
      return
    }

    taskStreamConversationIds.value = [...taskStreamConversationIds.value, conversationId]
  }

  function isCurrentTaskStream(taskStreamToken: number | null | undefined) {
    if (!taskStreamToken) {
      return false
    }

    return shouldApplyTaskProgressForStream({
      currentTaskStreamToken: currentTaskStreamToken.value,
      streamToken: taskStreamToken,
      activeConversationId: activeConversationId.value,
      streamConversationIds: taskStreamConversationIds.value
    })
  }

  function markTaskProgressFailedForCurrentStream(errorMessage: string, taskStreamToken = currentTaskStreamToken.value) {
    if (!isCurrentTaskStream(taskStreamToken)) {
      return
    }

    activeTaskProgress.value = markTaskProgressTerminal(activeTaskProgress.value, 'FAILED', errorMessage)
  }

  function markTaskProgressStoppedForCurrentStream(errorMessage: string, taskStreamToken = currentTaskStreamToken.value) {
    if (!isCurrentTaskStream(taskStreamToken)) {
      return
    }

    activeTaskProgress.value = markTaskProgressTerminal(activeTaskProgress.value, 'STOPPED', errorMessage)
  }

  async function restoreActiveTaskProgress(conversationId: number | null) {
    if (!conversationId || conversationId < 1) {
      activeTaskProgress.value = null
      return
    }

    try {
      const progress = await fetchLatestTaskProgress(conversationId)
      if (activeConversationId.value === conversationId) {
        applyTaskProgress(progress)
      }
    } catch {
      if (activeConversationId.value === conversationId) {
        activeTaskProgress.value = null
      }
    }
  }

  return {
    activeTaskProgress,
    currentTaskStreamToken,
    applyTaskProgress,
    activateTaskStreamContext,
    deactivateTaskStreamContext,
    registerTaskStreamConversationId,
    isCurrentTaskStream,
    markTaskProgressFailedForCurrentStream,
    markTaskProgressStoppedForCurrentStream,
    restoreActiveTaskProgress
  }
}
