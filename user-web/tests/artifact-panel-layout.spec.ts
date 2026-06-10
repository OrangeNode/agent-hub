import { describe, expect, it } from 'vitest'
import chatViewSource from '../src/views/ChatView.vue?raw'

describe('artifact panel layout', () => {
  it('does not render a top-right all-files button', () => {
    expect(chatViewSource).not.toContain('toggleWorkPanel')
    expect(chatViewSource).not.toContain("{{ workPanelOpen ? '隐藏面板' : '全部文件' }}")
  })

  it('keeps file preview in the large modal instead of the side panel', () => {
    expect(chatViewSource).toContain('class="artifact-preview-dialog"')
    expect(chatViewSource).toContain('class="artifact-code-preview"')
    expect(chatViewSource).not.toContain('class="artifact-preview-section"')
  })
})
