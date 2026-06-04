import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({
  breaks: true,
  linkify: true
})

function removeDangerousMarkdownLinks(content: string) {
  return content.replace(/\]\(\s*javascript:[^)]+\)/gi, '](#)')
}

export function renderAssistantMarkdown(content: string) {
  return DOMPurify.sanitize(markdown.render(removeDangerousMarkdownLinks(content || '')))
}
