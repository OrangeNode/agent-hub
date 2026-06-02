import type { ApiResponse } from './http'
import { http } from './http'

export interface ConversationRecord {
  conversationId: number
  title: string
  agentKey?: string | null
  modelId?: number | null
  status: string
}

export interface ChatMessageRecord {
  messageId: number
  conversationId: number
  role: 'USER' | 'ASSISTANT' | 'user' | 'assistant'
  content: string
  reasoningContent?: string | null
  status: string
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
  defaultModel: boolean
  enabled: boolean
  sort: number
}

export interface OpenChatStreamOptions {
  prompt: string
  token: string
  conversationId?: number | null
  modelId?: number | null
  agentKey?: string | null
}

export function openChatStream(options: OpenChatStreamOptions): EventSource {
  const url = new URL('/api/chat/stream', window.location.origin)
  url.searchParams.set('prompt', options.prompt)
  url.searchParams.set('token', options.token)

  if (options.conversationId) {
    url.searchParams.set('conversationId', String(options.conversationId))
  }

  if (options.modelId) {
    url.searchParams.set('modelId', String(options.modelId))
  }

  if (options.agentKey) {
    url.searchParams.set('agentKey', options.agentKey)
  }

  return new EventSource(url)
}

export async function fetchConversations() {
  const response = await http.get<ApiResponse<ConversationRecord[]>>('/chat/conversations')
  return response.data.data
}

export async function fetchConversationMessages(conversationId: number) {
  const response = await http.get<ApiResponse<ChatMessageRecord[]>>(`/chat/conversations/${conversationId}/messages`)
  return response.data.data
}

export async function deleteConversation(conversationId: number) {
  await http.delete(`/chat/conversations/${conversationId}`)
}

export async function fetchAvailableModels() {
  const response = await http.get<ApiResponse<AvailableModelRecord[]>>('/ai/models/available')
  return response.data.data
}
