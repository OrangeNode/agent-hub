import { describe, expect, it } from 'vitest'
import chatViewSource from '../src/views/ChatView.vue?raw'
import artifactPanelsSource from '../src/views/chat/ChatArtifactPanels.vue?raw'

const normalizedArtifactPanelsSource = artifactPanelsSource.replace(/\r\n/g, '\n')

describe('artifact panel layout', () => {
  it('does not render a top-right all-files button', () => {
    expect(chatViewSource).not.toContain('toggleWorkPanel')
    expect(chatViewSource).not.toContain("{{ workPanelOpen ? '隐藏面板' : '全部文件' }}")
  })

  it('opens file preview in a right-side panel instead of a centered modal', () => {
    expect(chatViewSource).toContain(`'preview-open': artifactPreviewOpen`)
    expect(chatViewSource).toContain(':preview-open="artifactPreviewOpen"')
    expect(artifactPanelsSource).toContain('<aside v-if="previewOpen" class="artifact-preview-panel"')
    expect(artifactPanelsSource).toContain('class="artifact-preview-dialog"')
    expect(artifactPanelsSource).toContain('class="artifact-code-preview"')
    expect(artifactPanelsSource).not.toContain('class="artifact-preview-modal"')
    expect(artifactPanelsSource).not.toContain('class="artifact-preview-section"')
  })

  it('keeps artifact panels as right-side drawers near the desktop breakpoint', () => {
    expect(normalizedArtifactPanelsSource).toContain('.work-panel,\n  .artifact-preview-panel {\n    position: fixed;')
    expect(artifactPanelsSource).toContain('right: 0;')
    expect(artifactPanelsSource).not.toContain('grid-column: 1 / -1;')
    expect(chatViewSource).not.toContain('grid-column: 1 / -1;')
  })
})
