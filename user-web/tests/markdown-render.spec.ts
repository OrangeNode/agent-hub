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
  it('should not render sandbox workspace paths as clickable download links', () => {
    const html = renderAssistantMarkdown(
      '您可以点击以下链接下载该文件：\n\n[下载 codex_full_chat_test.txt](/workspace/artifacts/codex_full_chat_test.txt)'
    )

    expect(html).not.toContain('<a ')
    expect(html).not.toContain('href="/workspace/artifacts/codex_full_chat_test.txt"')
    expect(html).toContain('下载 codex_full_chat_test.txt')
    expect(html).toContain('请使用下方文件卡片下载')
  })

  it('should not render local workspace URLs as clickable download links', () => {
    const html = renderAssistantMarkdown(
      '[下载 codex_full_chat_test.txt](http://127.0.0.1:5174/workspace/artifacts/codex_full_chat_test.txt)'
    )

    expect(html).not.toContain('<a ')
    expect(html).not.toContain('127.0.0.1:5174/workspace/artifacts')
    expect(html).toContain('下载 codex_full_chat_test.txt')
    expect(html).toContain('请使用下方文件卡片下载')
  })
})
