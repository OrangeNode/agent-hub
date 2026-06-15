import { describe, expect, it } from 'vitest'
import { isMarkdownPath, renderMarkdownPreview } from '../src/utils/markdown-preview'

describe('markdown-preview', () => {
  it('renders common markdown blocks and inline marks', () => {
    const html = renderMarkdownPreview(`# 标题

**重点** 和 \`code\`

\`\`\`ts
const value = '<safe>'
\`\`\`
`)

    expect(html).toContain('<h1>标题</h1>')
    expect(html).toContain('<strong>重点</strong>')
    expect(html).toContain('<code>code</code>')
    expect(html).toContain('<pre><code class="language-ts">')
    expect(html).toContain('&lt;safe&gt;')
  })

  it('escapes html before rendering markdown syntax', () => {
    const html = renderMarkdownPreview('<img src=x onerror=alert(1)>')

    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;')
    expect(html).not.toContain('<img')
  })

  it('detects markdown file paths', () => {
    expect(isMarkdownPath('SKILL.md')).toBe(true)
    expect(isMarkdownPath('references/openxml-sdk-reference.markdown')).toBe(true)
    expect(isMarkdownPath('scripts/run.py')).toBe(false)
  })
})
