import { describe, expect, it } from 'vitest'
import {
  groupConversationsByRecency,
  type ConversationItem
} from '../src/views/chat-agent.helpers'

describe('聊天会话时间分组', () => {
  const now = new Date('2026-06-22T12:00:00+08:00')

  it('应该按最近、三天内、更久分组并保留原始顺序', () => {
    const conversations: ConversationItem[] = [
      conversation(1, '刚聊过', '2026-06-22T10:30:00+08:00'),
      conversation(2, '昨天', '2026-06-21T08:00:00+08:00'),
      conversation(3, '三天前', '2026-06-19T12:00:00+08:00'),
      conversation(4, '更早', '2026-06-18T11:59:59+08:00')
    ]

    const groups = groupConversationsByRecency(conversations, now)

    expect(groups.map((group) => ({
      title: group.title,
      ids: group.items.map((item) => item.id)
    }))).toEqual([
      { title: '最近', ids: [1] },
      { title: '三天内', ids: [2, 3] },
      { title: '更久', ids: [4] }
    ])
  })

  it('无更新时间的会话应该归入更久', () => {
    const groups = groupConversationsByRecency([
      conversation(1, '无时间', '')
    ], now)

    expect(groups).toEqual([
      {
        key: 'older',
        title: '更久',
        items: [conversation(1, '无时间', '')]
      }
    ])
  })
})

function conversation(id: number, title: string, updatedAt: string): ConversationItem {
  return {
    id,
    title,
    updatedAt,
    agentKey: null
  }
}
