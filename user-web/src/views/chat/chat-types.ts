import type { DropdownOption } from 'naive-ui'

import type { ChatProcessEvent } from '../chat-tool-events.helpers'

export type ChatRole = 'user' | 'assistant'
export type ThinkingMode = 'instant' | 'thinking'

export interface ChatAttachmentItem {
  attachmentId: number
  filename?: string | null
  contentType?: string | null
  status?: string | null
  expired: boolean
  previewUrl?: string | null
  message?: string | null
  messageId?: number | null
}

export interface ChatMessageItem {
  id: number
  role: ChatRole
  content: string
  errorMessage?: string | null
  reasoningContent?: string
  processExpanded?: boolean
  reasoningStartedAt?: number
  reasoningFinishedAt?: number
  status?: string
  attachments?: ChatAttachmentItem[]
  processEvents?: ChatProcessEvent[]
}

export interface ChatArtifactItem {
  artifactId: number
  fileName: string
  relativePath: string
  contentType: string
  sizeBytes: number
  sha256?: string | null
  version?: number | null
}

export interface AgentOption {
  key: string
  name: string
  shortName: string
  description: string
}

export interface PendingImage {
  localId: string
  file: File
  kind: 'image' | 'file'
  contentType: string
  previewUrl: string | null
  attachmentId?: number
  progress: number
  status: 'uploading' | 'ready' | 'failed'
  error?: string
}

export type UploadMenuOption = DropdownOption

/**
 * 组合式函数通过它向用户反馈提示，避免直接依赖 naive-ui 的 useMessage 上下文。
 */
export interface ChatNotifier {
  info: (content: string) => void
  success: (content: string) => void
  warning: (content: string) => void
  error: (content: string) => void
}
