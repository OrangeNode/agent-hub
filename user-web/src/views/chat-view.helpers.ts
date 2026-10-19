// 聊天页的纯函数逻辑，不依赖组件状态，便于单独测试。
export interface ReusableImageAttachmentLike {
  attachmentId: number
  expired: boolean
  previewUrl?: string | null
}

export function selectReusableImageAttachments<T extends ReusableImageAttachmentLike>(attachments: T[]) {
  return attachments.filter((item) => !item.expired && Boolean(item.previewUrl))
}

export function validateReuseImageCount(pendingCount: number, reuseCount: number) {
  if (pendingCount + reuseCount > 8) {
    return '单条消息最多上传 8 张图片'
  }

  return null
}

export interface SendButtonDisabledState {
  streaming: boolean
  hasUploadingImage: boolean
  hasFailedImage: boolean
  canSubmitContent: boolean
  imageInputBlocked: boolean
}

export function resolveSendButtonDisabled(state: SendButtonDisabledState) {
  if (state.streaming) {
    return false
  }

  return state.hasUploadingImage
    || state.hasFailedImage
    || !state.canSubmitContent
    || state.imageInputBlocked
}

export interface AssistantDisplayMessageLike {
  content?: string | null
  errorMessage?: string | null
  status?: string | null
}

export function assistantDisplayContent(messageItem: AssistantDisplayMessageLike) {
  if (messageItem.content) {
    return messageItem.content
  }

  if (messageItem.status === 'FAILED') {
    return messageItem.errorMessage || '生成失败'
  }

  if (messageItem.status === 'STOPPED') {
    return messageItem.errorMessage || '已中止生成'
  }

  return '正在生成回复...'
}

export interface StoppableAssistantMessageLike {
  role: string
  status?: string | null
}

export interface StreamingMessageStateLike {
  status?: string | null
}

export interface StreamAssistantMessageLike {
  id: number
}

export interface ConversationSwitchStopState {
  streaming: boolean
  assistantMessageId?: number | null
}

export function resolveConversationSwitchStopAction(state: ConversationSwitchStopState) {
  if (!state.streaming) {
    return {
      shouldStop: false,
      messageId: null
    }
  }

  const messageId = state.assistantMessageId && state.assistantMessageId > 0
    ? state.assistantMessageId
    : null

  return {
    shouldStop: true,
    messageId
  }
}

export function findStoppableAssistantMessage<T extends StoppableAssistantMessageLike>(messages: T[]) {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const messageItem = messages[index]
    if (messageItem.role === 'assistant' && shouldApplyStreamingEvent(messageItem)) {
      return messageItem
    }
  }

  return undefined
}

export function shouldApplyStreamingEvent<T extends StreamingMessageStateLike>(messageItem?: T): messageItem is T {
  return messageItem?.status === 'STREAMING'
}

export function applyMessageCompletedIfStreaming<T extends StreamingMessageStateLike>(
  messageItem: T | undefined,
  status?: string | null
) {
  if (!shouldApplyStreamingEvent(messageItem)) {
    return false
  }

  messageItem.status = status ?? 'COMPLETED'
  return true
}

export function applyStreamAssistantMessageId<T extends StreamAssistantMessageLike>(
  messages: T[],
  currentMessageId: number,
  streamMessageId?: number | null
) {
  if (!streamMessageId || streamMessageId <= 0) {
    return currentMessageId
  }

  const assistantMessage = messages.find((messageItem) => messageItem.id === currentMessageId)
  if (!assistantMessage) {
    return currentMessageId
  }

  assistantMessage.id = streamMessageId
  return streamMessageId
}

export function applyStreamConversationId<T>(
  messageMap: Record<number, T[]>,
  currentConversationId: number | null,
  streamConversationId?: number | null
) {
  if (!currentConversationId || !streamConversationId || streamConversationId <= 0) {
    return currentConversationId
  }

  if (currentConversationId > 0) {
    return currentConversationId
  }

  messageMap[streamConversationId] = messageMap[currentConversationId] ?? []
  delete messageMap[currentConversationId]
  return streamConversationId
}

export interface TaskStreamGuardState {
  currentTaskStreamToken: number | null
  streamToken: number
  activeConversationId: number | null
  streamConversationIds: Array<number | null | undefined>
}

export function shouldApplyTaskProgressForStream(state: TaskStreamGuardState) {
  if (!state.currentTaskStreamToken || state.currentTaskStreamToken !== state.streamToken || !state.activeConversationId) {
    return false
  }

  return state.streamConversationIds.some((conversationId) => conversationId === state.activeConversationId)
}

export type TaskProgressTerminalStatus = 'FAILED' | 'STOPPED'

export interface TaskProgressTerminalStepLike {
  status: string
  errorMessage?: string | null
}

export interface TaskProgressTerminalLike {
  status: string
  errorMessage?: string | null
  steps: TaskProgressTerminalStepLike[]
}

export function markTaskProgressTerminal<T extends TaskProgressTerminalLike>(
  progress: T | null | undefined,
  status: TaskProgressTerminalStatus,
  errorMessage?: string | null
) {
  if (!progress) {
    return null
  }

  const terminalMessage = errorMessage ?? progress.errorMessage ?? null
  return {
    ...progress,
    status,
    errorMessage: terminalMessage,
    steps: progress.steps.map((step) =>
      step.status === 'RUNNING'
        ? { ...step, status, errorMessage: terminalMessage }
        : { ...step }
    )
  } as T
}

export interface NewConversationState {
  activeConversationId: number | null
  activeAgentKey: string | null
  activeAgentName: string
  selectedSkillIds: number[]
  prompt: string
  workPanelOpen: boolean
  artifactPreviewOpen: boolean
  artifactPreview: unknown | null
  selectedArtifactId: number | null
  skillPickerOpen: boolean
  historyImagePickerOpen: boolean
  thinkingConfigOpen: boolean
}

export interface NewConversationResetOptions {
  preserveAgent?: boolean
}

export function resolveNewConversationState(
  state: NewConversationState,
  options: NewConversationResetOptions = {}
): NewConversationState {
  return {
    activeConversationId: null,
    activeAgentKey: options.preserveAgent ? state.activeAgentKey : null,
    activeAgentName: options.preserveAgent ? state.activeAgentName : '通用 Agent',
    selectedSkillIds: [],
    prompt: '',
    workPanelOpen: false,
    artifactPreviewOpen: false,
    artifactPreview: null,
    selectedArtifactId: null,
    skillPickerOpen: false,
    historyImagePickerOpen: false,
    thinkingConfigOpen: false
  }
}
