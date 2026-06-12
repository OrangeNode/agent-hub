import { describe, expect, it } from 'vitest'
import chatViewSource from '../src/views/ChatView.vue?raw'

describe('artifact bundle style', () => {
  it('opens the all-files panel from both the card and preview button', () => {
    expect(chatViewSource).toContain('@click="openArtifactPanel()"')
    expect(chatViewSource).toContain('@keydown.enter.prevent="openArtifactPanel()"')
    expect(chatViewSource).toContain('@click.stop="openArtifactPanel()"')
  })

  it('uses the chat column width instead of a narrower fixed width', () => {
    const match = chatViewSource.match(/\.artifact-bundle-card\s*\{(?<body>[\s\S]*?)\n\}/)

    expect(match?.groups?.body).toContain('width: min(100%, var(--chat-column-width));')
    expect(match?.groups?.body).not.toContain('580px')
  })
})
