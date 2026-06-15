const MARKDOWN_EXTENSIONS = ['.md', '.markdown']

export function isMarkdownPath(path: string) {
  const lowerPath = path.toLowerCase()
  return MARKDOWN_EXTENSIONS.some((extension) => lowerPath.endsWith(extension))
}

export function renderMarkdownPreview(markdown: string) {
  const lines = markdown.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
  const html: string[] = []
  let index = 0
  let listType: 'ul' | 'ol' | null = null

  const closeList = () => {
    if (listType) {
      html.push(`</${listType}>`)
      listType = null
    }
  }

  while (index < lines.length) {
    const line = lines[index]
    const trimmed = line.trim()

    if (!trimmed) {
      closeList()
      index++
      continue
    }

    const fenceMatch = trimmed.match(/^```(\w+)?\s*$/)
    if (fenceMatch) {
      closeList()
      const language = fenceMatch[1] ? ` class="language-${escapeAttribute(fenceMatch[1])}"` : ''
      const codeLines: string[] = []
      index++
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        codeLines.push(lines[index])
        index++
      }
      if (index < lines.length) {
        index++
      }
      html.push(`<pre><code${language}>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
      continue
    }

    const headingMatch = trimmed.match(/^(#{1,6})\s+(.+)$/)
    if (headingMatch) {
      closeList()
      const level = headingMatch[1].length
      html.push(`<h${level}>${renderInline(headingMatch[2])}</h${level}>`)
      index++
      continue
    }

    if (/^[-*_]{3,}$/.test(trimmed)) {
      closeList()
      html.push('<hr>')
      index++
      continue
    }

    const unorderedMatch = trimmed.match(/^[-*+]\s+(.+)$/)
    if (unorderedMatch) {
      if (listType !== 'ul') {
        closeList()
        html.push('<ul>')
        listType = 'ul'
      }
      html.push(`<li>${renderInline(unorderedMatch[1])}</li>`)
      index++
      continue
    }

    const orderedMatch = trimmed.match(/^\d+\.\s+(.+)$/)
    if (orderedMatch) {
      if (listType !== 'ol') {
        closeList()
        html.push('<ol>')
        listType = 'ol'
      }
      html.push(`<li>${renderInline(orderedMatch[1])}</li>`)
      index++
      continue
    }

    const quoteMatch = trimmed.match(/^>\s?(.+)$/)
    if (quoteMatch) {
      closeList()
      html.push(`<blockquote><p>${renderInline(quoteMatch[1])}</p></blockquote>`)
      index++
      continue
    }

    closeList()
    html.push(`<p>${renderInline(trimmed)}</p>`)
    index++
  }

  closeList()
  return html.join('\n')
}

function renderInline(value: string) {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_]+)__/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
    .replace(/_([^_]+)_/g, '<em>$1</em>')
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function escapeAttribute(value: string) {
  return value.replace(/[^a-zA-Z0-9_-]/g, '')
}
