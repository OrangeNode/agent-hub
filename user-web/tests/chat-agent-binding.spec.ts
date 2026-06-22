import { describe, expect, it } from 'vitest'
import {
  canClearAgentSelection,
  mapConversationRecord,
  resolveAgentSelectionForConversation
} from '../src/views/chat-agent.helpers'

describe('聊天会话 Agent 绑定', () => {
  it('应该保留历史会话的 Agent 标识', () => {
    expect(mapConversationRecord({
      conversationId: 100,
      title: '历史会话',
      agentKey: 'official-writing',
      modelId: 10,
      status: 'ACTIVE',
      updatedAt: '2026-06-22T10:00:00+08:00'
    })).toEqual({
      id: 100,
      title: '历史会话',
      updatedAt: '2026-06-22T10:00:00+08:00',
      agentKey: 'official-writing'
    })
  })

  it('已有消息的 Agent 会话不允许清空 Agent', () => {
    expect(canClearAgentSelection({
      activeConversation: {
        id: 100,
        title: '历史会话',
        updatedAt: '2026-06-22T10:00:00+08:00',
        agentKey: 'official-writing'
      },
      messageCount: 2
    })).toBe(false)
  })

  it('切回历史会话时应该恢复绑定的 Agent 展示', () => {
    expect(resolveAgentSelectionForConversation({
      conversation: {
        id: 100,
        title: '历史会话',
        updatedAt: '2026-06-22T10:00:00+08:00',
        agentKey: 'official-writing'
      },
      agents: [
        {
          key: 'official-writing',
          name: '公文写作 Agent',
          shortName: '公',
          description: '协助起草公文'
        }
      ],
      fallbackName: '通用 Agent'
    })).toEqual({
      key: 'official-writing',
      name: '公文写作 Agent'
    })
  })
})
