import { describe, expect, it } from 'vitest'
import { renderAssistantMarkdown } from '../src/views/message-render.helpers'

describe('助手消息 Markdown 渲染', () => {
  it('应该把正文渲染成 Markdown HTML', () => {
    const html = renderAssistantMarkdown('**重点**\n\n- 第一项\n- 第二项')

    expect(html).toContain('<strong>重点</strong>')
    expect(html).toContain('<ul>')
    expect(html).toContain('<li>第一项</li>')
  })

  it('应该清理危险 HTML', () => {
    const html = renderAssistantMarkdown('<script>alert(1)</script>\n\n[链接](javascript:alert(1))')

    expect(html).not.toContain('<script>')
    expect(html).not.toContain('javascript:')
  })
})
