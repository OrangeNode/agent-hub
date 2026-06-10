import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import markdown from 'highlight.js/lib/languages/markdown'
import python from 'highlight.js/lib/languages/python'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

export type ArtifactPreviewMode = 'code' | 'text' | 'unsupported'

const EXTENSION_LANGUAGE_MAP: Record<string, string> = {
  bash: 'bash',
  cjs: 'javascript',
  css: 'css',
  html: 'xml',
  java: 'java',
  js: 'javascript',
  json: 'json',
  jsx: 'javascript',
  md: 'markdown',
  mjs: 'javascript',
  py: 'python',
  sh: 'bash',
  ts: 'typescript',
  tsx: 'typescript',
  vue: 'xml',
  xml: 'xml',
  yaml: 'yaml',
  yml: 'yaml'
}

const PLAIN_TEXT_EXTENSIONS = new Set(['csv', 'log', 'text', 'txt'])
const PLAIN_TEXT_CONTENT_TYPES = ['text/plain', 'text/csv']

let languagesRegistered = false

function ensureLanguagesRegistered() {
  if (languagesRegistered) {
    return
  }
  hljs.registerLanguage('bash', bash)
  hljs.registerLanguage('css', css)
  hljs.registerLanguage('java', java)
  hljs.registerLanguage('javascript', javascript)
  hljs.registerLanguage('json', json)
  hljs.registerLanguage('markdown', markdown)
  hljs.registerLanguage('python', python)
  hljs.registerLanguage('typescript', typescript)
  hljs.registerLanguage('xml', xml)
  hljs.registerLanguage('yaml', yaml)
  languagesRegistered = true
}

export function artifactFileExtension(fileName: string) {
  const normalized = fileName.trim().toLowerCase()
  const dotIndex = normalized.lastIndexOf('.')
  return dotIndex >= 0 ? normalized.slice(dotIndex + 1) : ''
}

export function languageFromArtifact(fileName: string, contentType?: string | null) {
  const extension = artifactFileExtension(fileName)
  if (EXTENSION_LANGUAGE_MAP[extension]) {
    return EXTENSION_LANGUAGE_MAP[extension]
  }

  const normalizedContentType = normalizeContentType(contentType)
  if (normalizedContentType.includes('json')) {
    return 'json'
  }
  if (normalizedContentType.includes('javascript')) {
    return 'javascript'
  }
  if (normalizedContentType.includes('typescript')) {
    return 'typescript'
  }
  if (normalizedContentType.includes('xml') || normalizedContentType.includes('html')) {
    return 'xml'
  }
  if (normalizedContentType.includes('css')) {
    return 'css'
  }
  return ''
}

export function resolveArtifactPreviewMode(options: {
  fileName: string
  contentType?: string | null
  previewable?: boolean | null
}) {
  if (options.previewable === false) {
    return 'unsupported' satisfies ArtifactPreviewMode
  }

  if (languageFromArtifact(options.fileName, options.contentType)) {
    return 'code' satisfies ArtifactPreviewMode
  }

  const extension = artifactFileExtension(options.fileName)
  const contentType = normalizeContentType(options.contentType)
  if (PLAIN_TEXT_EXTENSIONS.has(extension)
      || PLAIN_TEXT_CONTENT_TYPES.some((type) => contentType.startsWith(type))) {
    return 'text' satisfies ArtifactPreviewMode
  }

  return options.previewable ? 'text' : 'unsupported'
}

export function renderHighlightedCode(content: string, language?: string | null) {
  ensureLanguagesRegistered()
  const normalizedLanguage = language?.trim() ?? ''
  if (normalizedLanguage && hljs.getLanguage(normalizedLanguage)) {
    return hljs.highlight(content, { language: normalizedLanguage, ignoreIllegals: true }).value
  }
  return hljs.highlightAuto(content).value
}

function normalizeContentType(contentType?: string | null) {
  return (contentType ?? '').split(';')[0]?.trim().toLowerCase() ?? ''
}

export function artifactPreviewTitle(mode: ArtifactPreviewMode) {
  if (mode === 'code') {
    return '代码预览'
  }
  if (mode === 'text') {
    return '文本预览'
  }
  return '暂不支持预览'
}
