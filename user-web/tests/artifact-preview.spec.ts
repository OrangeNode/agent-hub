import { describe, expect, it } from 'vitest'
import {
  artifactPreviewTitle,
  languageFromArtifact,
  renderHighlightedCode,
  resolveArtifactPreviewMode
} from '../src/views/artifact-preview.helpers'

describe('artifact preview helpers', () => {
  it('treats common source files as highlighted code previews', () => {
    expect(resolveArtifactPreviewMode({
      fileName: 'quick_sort.py',
      contentType: 'application/octet-stream',
      previewable: true
    })).toBe('code')
    expect(languageFromArtifact('quick_sort.py', 'application/octet-stream')).toBe('python')
  })

  it('keeps ordinary txt files in plain text preview mode', () => {
    expect(resolveArtifactPreviewMode({
      fileName: 'input.txt',
      contentType: 'text/plain',
      previewable: true
    })).toBe('text')
  })

  it('leaves office documents for later preview adapters', () => {
    expect(resolveArtifactPreviewMode({
      fileName: 'report.docx',
      contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      previewable: false
    })).toBe('unsupported')
    expect(artifactPreviewTitle('unsupported')).toBe('暂不支持预览')
  })

  it('renders code through highlight.js', () => {
    const html = renderHighlightedCode('def quick_sort(items):\n    return items', 'python')

    expect(html).toContain('hljs-keyword')
    expect(html).toContain('quick_sort')
  })
})
