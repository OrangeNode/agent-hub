import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({
  breaks: true,
  linkify: true
})

const BLOCKED_SANDBOX_LINK_HINT = '请使用下方文件卡片下载'
const LOCAL_DOWNLOAD_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]', '::1'])

function removeDangerousMarkdownLinks(content: string) {
  return content.replace(/\]\(\s*javascript:[^)]+\)/gi, '](#)')
}

function isSandboxWorkspaceHref(href: string | null) {
  const value = href?.trim()
  if (!value) {
    return false
  }
  if (value === '/workspace' || value.startsWith('/workspace/')) {
    return true
  }
  if (value === 'workspace' || value.startsWith('workspace/')) {
    return true
  }
  if (!/^[a-z][a-z\d+.-]*:/i.test(value)) {
    return false
  }
  try {
    const url = new URL(value)
    return LOCAL_DOWNLOAD_HOSTS.has(url.hostname) && (url.pathname === '/workspace' || url.pathname.startsWith('/workspace/'))
  } catch {
    return false
  }
}

function replaceSandboxWorkspaceLinks(html: string) {
  if (typeof document === 'undefined') {
    return html
  }
  const template = document.createElement('template')
  template.innerHTML = html
  template.content.querySelectorAll('a').forEach((link) => {
    if (!isSandboxWorkspaceHref(link.getAttribute('href'))) {
      return
    }
    const label = link.textContent?.trim() || link.getAttribute('href') || '文件'
    link.replaceWith(document.createTextNode(`${label}（${BLOCKED_SANDBOX_LINK_HINT}）`))
  })
  return template.innerHTML
}

export function renderAssistantMarkdown(content: string) {
  const safeHtml = DOMPurify.sanitize(markdown.render(removeDangerousMarkdownLinks(content || '')))
  return replaceSandboxWorkspaceLinks(safeHtml)
}
