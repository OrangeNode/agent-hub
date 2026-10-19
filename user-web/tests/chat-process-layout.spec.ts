import { describe, expect, it } from 'vitest'
import chatViewSource from '../src/views/chat/ChatMessageList.vue?raw'

function cssBlock(selector: string) {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = chatViewSource.match(new RegExp(`${escapedSelector}\\s*\\{(?<body>[\\s\\S]*?)\\n\\}`))
  return match?.groups?.body ?? ''
}

describe('思考过程展示结构', () => {
  it('应该使用明确的展开和收起思考过程入口', () => {
    expect(chatViewSource).toContain('{{ processToggleText(messageItem) }}')
    expect(chatViewSource).toContain("return messageItem.processExpanded ? '收起思考过程↑' : '展开思考过程↓'")
  })

  it('每段思考内容应该放在可滚动内容框中', () => {
    const reasoningScroll = cssBlock('.process-reasoning-scroll')

    expect(chatViewSource).toContain('class="process-reasoning-scroll"')
    expect(reasoningScroll).toContain('max-height:')
    expect(reasoningScroll).toContain('overflow-y: auto;')
  })

  it('工具调用应该通过按钮展开显示结果', () => {
    expect(chatViewSource).toContain('class="process-tool-toggle"')
    expect(chatViewSource).toContain(':aria-expanded="event.tool.expanded"')
    expect(chatViewSource).toContain('@click="toggleToolResult(event.tool)"')
    expect(chatViewSource).toContain('v-if="event.tool.expanded"')
  })

  it('展开和折叠应该使用过渡动画', () => {
    expect(chatViewSource).toContain('<Transition name="process-expand">')
    expect(chatViewSource).toContain('<Transition name="tool-detail">')
    expect(chatViewSource).toContain('.process-expand-enter-active,')
    expect(chatViewSource).toContain('.tool-detail-enter-active,')
    expect(chatViewSource).toContain('transition: max-height 0.22s ease, opacity 0.18s ease, transform 0.22s ease;')
  })
})
