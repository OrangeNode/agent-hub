import { describe, expect, it } from 'vitest'
import chatViewSource from '../src/views/ChatView.vue?raw'

const normalizedChatViewSource = chatViewSource.replace(/\r\n/g, '\n')

function cssBlock(selector: string) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = normalizedChatViewSource.match(new RegExp(`${escapedSelector}\\s*\\{(?<body>[\\s\\S]*?)\\n\\}`))
  return match?.groups?.body ?? ''
}

describe('聊天输入区 Skill 标签', () => {
  it('移除按钮应该重置浏览器默认按钮外观', () => {
    const closeButton = cssBlock('.skill-tag-close')

    expect(closeButton).toContain('padding: 0;')
    expect(closeButton).toContain('border: 0;')
    expect(closeButton).toContain('background: transparent;')
    expect(closeButton).toContain('cursor: pointer;')
  })
})
