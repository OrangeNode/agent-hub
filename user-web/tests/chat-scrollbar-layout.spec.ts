import { describe, expect, it } from 'vitest'
import chatViewSource from '../src/views/ChatView.vue?raw'

const normalizedChatViewSource = chatViewSource.replace(/\r\n/g, '\n')

function cssBlock(selector: string) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = normalizedChatViewSource.match(new RegExp(`${escapedSelector}\\s*\\{(?<body>[\\s\\S]*?)\\n\\}`))
  return match?.groups?.body ?? ''
}

describe('chat scrollbar layout', () => {
  it('uses the full browser-height conversation container as the scroll host', () => {
    const conversationView = cssBlock('.conversation-view')
    const messageList = cssBlock('.message-list')

    expect(conversationView).toContain('height: 100vh;')
    expect(conversationView).toContain('overflow-y: auto;')
    expect(messageList).not.toContain('overflow-y: auto;')
  })

  it('lightens recent chat and conversation scrollbars', () => {
    expect(chatViewSource).toContain('.recent-section,')
    expect(chatViewSource).toContain('.conversation-view {')
    expect(chatViewSource).toContain('scrollbar-color: #cfd4dc transparent;')
    expect(chatViewSource).toContain('.recent-section::-webkit-scrollbar-thumb')
    expect(chatViewSource).toContain('.conversation-view::-webkit-scrollbar-thumb')
  })

  it('prevents side drawer animation from creating a horizontal page scrollbar', () => {
    const chatShell = cssBlock('.chat-shell')

    expect(normalizedChatViewSource).toContain(':global(html),\n:global(body) {')
    expect(normalizedChatViewSource).toContain('overflow-x: hidden;')
    expect(chatShell).toContain('overflow-x: hidden;')
  })
})
