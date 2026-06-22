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

export type ConversationGroupKey = 'recent' | 'threeDays' | 'older'

export interface ConversationGroup {
  key: ConversationGroupKey
  title: string
  items: ConversationItem[]
}

export interface AgentSelection {
  key: string | null
  name: string
}

export function mapConversationRecord(item: ConversationRecord): ConversationItem {
  return {
    id: item.conversationId,
    title: item.title,
    updatedAt: item.updatedAt ?? '',
    agentKey: item.agentKey ?? null
  }
}

export function groupConversationsByRecency(
  conversations: ConversationItem[],
  now: Date = new Date()
): ConversationGroup[] {
  const groups: ConversationGroup[] = [
    { key: 'recent', title: '最近', items: [] },
    { key: 'threeDays', title: '三天内', items: [] },
    { key: 'older', title: '更久', items: [] }
  ]
  const groupMap = new Map(groups.map((group) => [group.key, group]))

  conversations.forEach((conversation) => {
    groupMap.get(resolveConversationGroupKey(conversation.updatedAt, now))?.items.push(conversation)
  })

  return groups.filter((group) => group.items.length > 0)
}

function resolveConversationGroupKey(updatedAt: string, now: Date): ConversationGroupKey {
  const updatedTime = Date.parse(updatedAt)
  if (!Number.isFinite(updatedTime)) {
    return 'older'
  }

  const diffMs = now.getTime() - updatedTime
  if (diffMs <= 24 * 60 * 60 * 1000) {
    return 'recent'
  }

  if (diffMs <= 3 * 24 * 60 * 60 * 1000) {
    return 'threeDays'
  }

  return 'older'
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
