import type { ConversationRecord } from '../api/chat'

export interface AgentOptionLike {
  key: string
  name: string
  shortName?: string
  description?: string
}

export interface ConversationItem {
  id: number
  title: string
  updatedAt: string
  agentKey?: string | null
}

export interface AgentSelection {
  key: string | null
  name: string
}

export function mapConversationRecord(item: ConversationRecord): ConversationItem {
  return {
    id: item.conversationId,
    title: item.title,
    updatedAt: '最近',
    agentKey: item.agentKey ?? null
  }
}

export function canClearAgentSelection(options: {
  activeConversation?: ConversationItem | null
  messageCount: number
}) {
  return !(options.activeConversation?.agentKey && options.messageCount > 0)
}

export function resolveAgentSelectionForConversation(options: {
  conversation?: ConversationItem | null
  agents: AgentOptionLike[]
  fallbackName: string
}): AgentSelection {
  const agentKey = options.conversation?.agentKey ?? null
  if (!agentKey) {
    return {
      key: null,
      name: options.fallbackName
    }
  }

  return {
    key: agentKey,
    name: options.agents.find((agent) => agent.key === agentKey)?.name ?? agentKey
  }
}
