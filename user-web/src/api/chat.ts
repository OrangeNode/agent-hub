import type { ApiResponse } from './http'
import { http } from './http'

export interface ConversationRecord {
  conversationId: number
  title: string
  agentKey?: string | null
  modelId?: number | null
  status: string
}

export interface ChatAttachmentRecord {
  attachmentId: number
  filename: string
  contentType: string
  status: string
  expired: boolean
  previewUrl?: string | null
  message?: string | null
  messageId?: number | null
}

export interface ChatToolTraceRecord {
  toolCallId: string
  name: string
  status: string
  stdout: string
  stderr: string
  result: string
}

export type ChatProcessEventRecord =
  | { type: 'reasoning'; content: string }
  | { type: 'tool'; tool: ChatToolTraceRecord }

export interface ChatMessageRecord {
  messageId: number
  conversationId: number
  role: 'USER' | 'ASSISTANT' | 'user' | 'assistant'
  content: string
  reasoningContent?: string | null
  status: string
  attachments?: ChatAttachmentRecord[]
  processEvents?: ChatProcessEventRecord[] | null
}

export interface ChatArtifactRecord {
  artifactId: number
  fileName: string
  relativePath: string
  contentType: string
  sizeBytes: number
  sha256?: string | null
  version?: number | null
}

export interface ChatArtifactContentRecord {
  artifactId: number
  fileName: string
  relativePath: string
  contentType: string
  sizeBytes: number
  previewable: boolean
  truncated: boolean
  content: string
}

export interface DownloadBlobResult {
  blob: Blob
  filename: string
}

export interface ImageUploadUrlRecord {
  attachmentId: number
  uploadUrl: string
  method: 'PUT'
  headers: Record<string, string>
  expiresAt: string
}

export interface AvailableModelRecord {
  modelId: number
  providerId: number
  providerCode: string
  providerName: string
  modelCode: string
  displayName: string
  capabilityType: string
  streamSupported: boolean
  reasoningSupported: boolean
  contextWindow?: number | null
  maxOutputTokens?: number | null
  imageInputSupported: boolean
  imageTokenEstimate?: number | null
  defaultModel: boolean
  enabled: boolean
  sort: number
}

export interface AvailableAgentRecord {
  agentId: number
  agentKey: string
  name: string
  shortName?: string | null
  description?: string | null
  defaultModelId?: number | null
  enabled: boolean
  sort: number
}

export interface AvailableSkillRecord {
  skillId: number
  skillKey: string
  name: string
  description?: string | null
}

export interface OpenChatStreamOptions {
  prompt: string
  token: string
  conversationId?: number | null
  modelId?: number | null
  agentKey?: string | null
  thinkingEnabled?: boolean
  attachmentIds?: number[]
  reuseAttachmentIds?: number[]
  skillIds?: number[]
}

export interface ChatStreamHandlers {
  onConversation?: (data: { conversationId: number; title: string }) => void
  onMessage?: (data: { messageId: number; role: string }) => void
  onReasoningDelta?: (data: { content: string }) => void
  onAnswerDelta?: (data: { content: string }) => void
  onToolCall?: (data: { toolCallId: string; name: string }) => void
  onToolOutput?: (data: { toolCallId: string; stream: string; content: string }) => void
  onToolResult?: (data: { toolCallId: string; name: string; status: string; content: string }) => void
  onMessageCompleted?: (data: { messageId: number; status: string }) => void
  onError?: (data: { code: string; message: string }) => void
  onDone?: () => void
  onTransportError?: (error: unknown) => void
}

interface ParsedSseEvent {
  event: string
  data: string
}

export function openChatStream(options: OpenChatStreamOptions, handlers: ChatStreamHandlers): AbortController {
  const controller = new AbortController()
  void readChatStream(options, handlers, controller)
  return controller
}

async function readChatStream(
  options: OpenChatStreamOptions,
  handlers: ChatStreamHandlers,
  controller: AbortController
) {
  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream',
        Authorization: `Bearer ${options.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        prompt: options.prompt,
        conversationId: options.conversationId ?? null,
        modelId: options.modelId ?? null,
        agentKey: options.agentKey ?? null,
        thinkingEnabled: options.thinkingEnabled ?? false,
        attachmentIds: options.attachmentIds ?? [],
        reuseAttachmentIds: options.reuseAttachmentIds ?? [],
        skillIds: options.skillIds ?? []
      }),
      signal: controller.signal
    })

    if (!response.ok) {
      throw new Error(`流式请求失败：${response.status}`)
    }

    if (!response.body) {
      throw new Error('浏览器不支持流式响应')
    }

    await readSseBody(response.body, handlers)
  } catch (error) {
    if (!controller.signal.aborted) {
      handlers.onTransportError?.(error)
    }
  }
}

async function readSseBody(body: ReadableStream<Uint8Array>, handlers: ChatStreamHandlers) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }

      buffer += decoder.decode(value, { stream: true })
      buffer = drainSseBuffer(buffer, handlers)
    }

    buffer += decoder.decode()
    drainSseBuffer(`${buffer}\n\n`, handlers)
  } finally {
    reader.releaseLock()
  }
}

function drainSseBuffer(buffer: string, handlers: ChatStreamHandlers) {
  let remaining = buffer

  while (true) {
    const separator = findEventSeparator(remaining)
    if (separator.index < 0) {
      return remaining
    }

    const rawEvent = remaining.slice(0, separator.index)
    remaining = remaining.slice(separator.index + separator.length)
    const event = parseSseEvent(rawEvent)
    if (event) {
      dispatchSseEvent(event, handlers)
    }
  }
}

function findEventSeparator(buffer: string) {
  const rn = buffer.indexOf('\r\n\r\n')
  const nn = buffer.indexOf('\n\n')

  if (rn < 0) {
    return { index: nn, length: 2 }
  }

  if (nn < 0 || rn < nn) {
    return { index: rn, length: 4 }
  }

  return { index: nn, length: 2 }
}

function parseSseEvent(rawEvent: string): ParsedSseEvent | null {
  const lines = rawEvent.split(/\r?\n/)
  let event = 'message'
  const data: string[] = []

  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      continue
    }

    if (line.startsWith('data:')) {
      data.push(line.slice(5).trimStart())
    }
  }

  if (!data.length) {
    return null
  }

  return {
    event,
    data: data.join('\n')
  }
}

function dispatchSseEvent(event: ParsedSseEvent, handlers: ChatStreamHandlers) {
  switch (event.event) {
    case 'conversation':
      dispatchJson(event.data, handlers.onConversation)
      break
    case 'message':
      dispatchJson(event.data, handlers.onMessage)
      break
    case 'reasoning_delta':
      dispatchJson(event.data, handlers.onReasoningDelta)
      break
    case 'answer_delta':
      dispatchJson(event.data, handlers.onAnswerDelta)
      break
    case 'tool_call':
      dispatchJson(event.data, handlers.onToolCall)
      break
    case 'tool_output':
      dispatchJson(event.data, handlers.onToolOutput)
      break
    case 'tool_result':
      dispatchJson(event.data, handlers.onToolResult)
      break
    case 'message_completed':
      dispatchJson(event.data, handlers.onMessageCompleted)
      break
    case 'error':
      dispatchJson(event.data, handlers.onError)
      break
    case 'done':
      handlers.onDone?.()
      break
  }
}

function dispatchJson<T>(data: string, handler: ((data: T) => void) | undefined) {
  if (!handler) {
    return
  }

  try {
    handler(JSON.parse(data) as T)
  } catch {
    // 忽略格式不完整的流事件，等待后续事件继续处理。
  }
}

export async function fetchConversations() {
  const response = await http.get<ApiResponse<ConversationRecord[]>>('/chat/conversations')
  return response.data.data
}

export async function fetchConversationMessages(conversationId: number) {
  const response = await http.get<ApiResponse<ChatMessageRecord[]>>(`/chat/conversations/${conversationId}/messages`)
  return response.data.data
}

export async function fetchConversationArtifacts(conversationId: number) {
  const response = await http.get<ApiResponse<ChatArtifactRecord[]>>(`/chat/conversations/${conversationId}/artifacts`)
  return response.data.data
}

export async function fetchArtifactContent(conversationId: number, artifactId: number) {
  const response = await http.get<ApiResponse<ChatArtifactContentRecord>>(
    `/chat/conversations/${conversationId}/artifacts/${artifactId}/content`
  )
  return response.data.data
}

export async function downloadArtifact(conversationId: number, artifactId: number, fallbackFilename: string) {
  const response = await http.get<Blob>(`/chat/conversations/${conversationId}/artifacts/${artifactId}/download`, {
    responseType: 'blob'
  })
  return {
    blob: response.data,
    filename: filenameFromDisposition(response.headers['content-disposition']) || fallbackFilename
  }
}

export async function downloadAllArtifacts(conversationId: number) {
  const response = await http.get<Blob>(`/chat/conversations/${conversationId}/artifacts/download`, {
    responseType: 'blob'
  })
  return {
    blob: response.data,
    filename: filenameFromDisposition(response.headers['content-disposition']) || '全部文件.zip'
  }
}

export async function deleteConversation(conversationId: number) {
  await http.delete(`/chat/conversations/${conversationId}`)
}

export async function renameConversation(conversationId: number, title: string) {
  await http.patch(`/chat/conversations/${conversationId}`, { title })
}

export async function fetchAvailableModels() {
  const response = await http.get<ApiResponse<AvailableModelRecord[]>>('/ai/models/available')
  return response.data.data
}

function filenameFromDisposition(disposition: string | undefined) {
  if (!disposition) {
    return ''
  }
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1])
    } catch {
      return utf8Match[1]
    }
  }
  const quotedMatch = disposition.match(/filename="([^"]+)"/i)
  if (quotedMatch?.[1]) {
    return quotedMatch[1]
  }
  const plainMatch = disposition.match(/filename=([^;]+)/i)
  return plainMatch?.[1]?.trim() ?? ''
}

export async function fetchAvailableAgents() {
  const response = await http.get<ApiResponse<AvailableAgentRecord[]>>('/agents/available')
  return response.data.data
}

export async function fetchAvailableSkills() {
  const response = await http.get<ApiResponse<AvailableSkillRecord[]>>('/skills/available')
  return response.data.data
}

export async function createImageUploadUrl(filename: string, contentType: string, fileSize: number) {
  const response = await http.post<ApiResponse<ImageUploadUrlRecord>>('/chat/attachments/image-upload-url', {
    filename,
    contentType,
    fileSize
  })
  return response.data.data
}

export async function confirmImageUpload(attachmentId: number) {
  await http.post(`/chat/attachments/${attachmentId}/confirm`)
}
