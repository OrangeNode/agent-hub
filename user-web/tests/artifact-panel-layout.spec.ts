import { describe, expect, it } from 'vitest'
import chatViewSource from '../src/views/ChatView.vue?raw'

describe('artifact panel layout', () => {
  it('does not render a top-right all-files button', () => {
    expect(chatViewSource).not.toContain('toggleWorkPanel')
    expect(chatViewSource).not.toContain("{{ workPanelOpen ? '隐藏面板' : '全部文件' }}")
  })

  it('opens file preview in a right-side panel instead of a centered modal', () => {
    expect(chatViewSource).toContain(`'preview-open': artifactPreviewOpen`)
    expect(chatViewSource).toContain('<aside v-if="artifactPreviewOpen" class="artifact-preview-panel"')
    expect(chatViewSource).toContain('class="artifact-preview-dialog"')
    expect(chatViewSource).toContain('class="artifact-code-preview"')
    expect(chatViewSource).not.toContain('class="artifact-preview-modal"')
    expect(chatViewSource).not.toContain('class="artifact-preview-section"')
  })

  it('keeps artifact panels as right-side drawers near the desktop breakpoint', () => {
    expect(chatViewSource).toContain('.work-panel,\n  .artifact-preview-panel {\n    position: fixed;')
    expect(chatViewSource).toContain('right: 0;')
    expect(chatViewSource).not.toContain('grid-column: 1 / -1;')
  })
})
