import { computed, ref } from 'vue'
import type { Ref } from 'vue'

import {
  downloadAllArtifacts,
  downloadArtifact,
  fetchArtifactContent,
  fetchConversationArtifacts
} from '../../api/chat'
import type { ChatArtifactContentRecord, ChatArtifactRecord } from '../../api/chat'
import {
  artifactPreviewTitle,
  languageFromArtifact,
  renderHighlightedCode,
  resolveArtifactPreviewMode
} from '../artifact-preview.helpers'
import type { ChatArtifactItem, ChatNotifier } from './chat-types'

interface UseChatArtifactsOptions {
  activeConversationId: Ref<number | null>
  notify: ChatNotifier
}

/**
 * 会话产物：文件列表、侧边面板、预览和下载。临时会话（负数 ID）没有产物。
 */
export function useChatArtifacts({ activeConversationId, notify }: UseChatArtifactsOptions) {
  const artifactMap = ref<Record<number, ChatArtifactItem[]>>({})
  const selectedArtifactId = ref<number | null>(null)
  const artifactPreview = ref<ChatArtifactContentRecord | null>(null)
  const workPanelOpen = ref(false)
  const artifactPreviewOpen = ref(false)
  const artifactsLoading = ref(false)
  const artifactPreviewLoading = ref(false)
  const artifactDownloading = ref(false)

  const currentArtifacts = computed(() =>
    activeConversationId.value && activeConversationId.value > 0
      ? artifactMap.value[activeConversationId.value] ?? []
      : []
  )
  const selectedArtifact = computed(() =>
    currentArtifacts.value.find((item) => item.artifactId === selectedArtifactId.value) ?? currentArtifacts.value[0] ?? null
  )
  const currentArtifactLanguage = computed(() => {
    const artifact = selectedArtifact.value
    if (!artifact) {
      return ''
    }
    return languageFromArtifact(artifact.fileName, artifactPreview.value?.contentType ?? artifact.contentType)
  })
  const currentArtifactPreviewMode = computed(() => {
    const artifact = selectedArtifact.value
    if (!artifact) {
      return 'unsupported'
    }
    return resolveArtifactPreviewMode({
      fileName: artifact.fileName,
      contentType: artifactPreview.value?.contentType ?? artifact.contentType,
      previewable: artifactPreview.value?.previewable ?? null
    })
  })
  const highlightedArtifactHtml = computed(() => {
    if (!artifactPreview.value?.content) {
      return ''
    }
    return renderHighlightedCode(artifactPreview.value.content, currentArtifactLanguage.value)
  })
  const artifactPreviewSubtitle = computed(() => {
    const artifact = selectedArtifact.value
    if (!artifact) {
      return '选择一个文件开始预览'
    }
    const modeTitle = artifactPreviewTitle(currentArtifactPreviewMode.value)
    return `${modeTitle} · ${formatFileSize(artifact.sizeBytes)}`
  })

  async function loadArtifacts(conversationId: number) {
    if (conversationId <= 0) {
      return
    }
    try {
      artifactsLoading.value = true
      const data = await fetchConversationArtifacts(conversationId)
      artifactMap.value[conversationId] = data.map(mapArtifact)
      if (!artifactMap.value[conversationId].some((item) => item.artifactId === selectedArtifactId.value)) {
        selectedArtifactId.value = artifactMap.value[conversationId][0]?.artifactId ?? null
        artifactPreview.value = null
      }
    } catch (error) {
      notify.error(error instanceof Error ? error.message : '加载文件失败')
    } finally {
      artifactsLoading.value = false
    }
  }

  async function refreshActiveArtifacts() {
    const conversationId = activeConversationId.value
    if (conversationId && conversationId > 0) {
      await loadArtifacts(conversationId)
    }
  }

  function forgetConversationArtifacts(conversationId: number) {
    delete artifactMap.value[conversationId]
  }

  function resetArtifactSelection() {
    selectedArtifactId.value = null
    artifactPreview.value = null
    artifactPreviewOpen.value = false
  }

  function closeArtifactPanel() {
    workPanelOpen.value = false
    artifactPreviewOpen.value = false
  }

  function closeArtifactPreview() {
    artifactPreviewOpen.value = false
  }

  async function openArtifactPanel(artifact?: ChatArtifactItem) {
    workPanelOpen.value = true
    const conversationId = activeConversationId.value
    if (!conversationId || conversationId <= 0) {
      return
    }
    if (!currentArtifacts.value.length) {
      await loadArtifacts(conversationId)
    }
    const target = artifact ?? null
    if (target) {
      await selectArtifact(target)
    }
  }

  async function selectArtifact(artifact: ChatArtifactItem) {
    const conversationId = activeConversationId.value
    if (!conversationId || conversationId <= 0) {
      return
    }
    selectedArtifactId.value = artifact.artifactId
    artifactPreviewOpen.value = true
    artifactPreview.value = null
    try {
      artifactPreviewLoading.value = true
      artifactPreview.value = await fetchArtifactContent(conversationId, artifact.artifactId)
    } catch (error) {
      notify.error(error instanceof Error ? error.message : '预览文件失败')
    } finally {
      artifactPreviewLoading.value = false
    }
  }

  async function downloadOneArtifact(artifact: ChatArtifactItem) {
    const conversationId = activeConversationId.value
    if (!conversationId || conversationId <= 0) {
      return
    }
    try {
      artifactDownloading.value = true
      const result = await downloadArtifact(conversationId, artifact.artifactId, artifact.fileName)
      saveBlob(result.blob, result.filename)
    } catch (error) {
      notify.error(error instanceof Error ? error.message : '下载文件失败')
    } finally {
      artifactDownloading.value = false
    }
  }

  async function downloadAllConversationArtifacts() {
    const conversationId = activeConversationId.value
    if (!conversationId || conversationId <= 0 || !currentArtifacts.value.length) {
      return
    }
    try {
      artifactDownloading.value = true
      const result = await downloadAllArtifacts(conversationId)
      saveBlob(result.blob, result.filename)
    } catch (error) {
      notify.error(error instanceof Error ? error.message : '下载全部文件失败')
    } finally {
      artifactDownloading.value = false
    }
  }

  return {
    selectedArtifactId,
    artifactPreview,
    workPanelOpen,
    artifactPreviewOpen,
    artifactsLoading,
    artifactPreviewLoading,
    artifactDownloading,
    currentArtifacts,
    selectedArtifact,
    currentArtifactLanguage,
    currentArtifactPreviewMode,
    highlightedArtifactHtml,
    artifactPreviewSubtitle,
    loadArtifacts,
    refreshActiveArtifacts,
    forgetConversationArtifacts,
    resetArtifactSelection,
    closeArtifactPanel,
    closeArtifactPreview,
    openArtifactPanel,
    selectArtifact,
    downloadOneArtifact,
    downloadAllConversationArtifacts
  }
}

export function formatFileSize(size: number | null | undefined) {
  const value = Number(size ?? 0)
  if (value < 1024) {
    return `${value} bytes`
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function mapArtifact(item: ChatArtifactRecord): ChatArtifactItem {
  return {
    artifactId: item.artifactId,
    fileName: item.fileName,
    relativePath: item.relativePath,
    contentType: item.contentType,
    sizeBytes: item.sizeBytes,
    sha256: item.sha256 ?? null,
    version: item.version ?? null
  }
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename || 'download'
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}
