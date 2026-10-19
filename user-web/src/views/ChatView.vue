<template>
  <div
    class="chat-shell"
    :class="{ 'panel-open': workPanelOpen, 'preview-open': artifactPreviewOpen, 'sidebar-collapsed': sidebarCollapsed }"
    @click="closeFloatingMenus"
  >
    <ChatSidebar
      ref="sidebarRef"
      :grouped-conversations="groupedConversations"
      :active-conversation-id="activeConversationId"
      :conversations-loading="conversationsLoading"
      :has-conversations="conversations.length > 0"
      :agent-options="agentOptions"
      :display-name="authStore.displayName || '用户'"
      :user-initial="userInitial"
      @collapse="sidebarCollapsed = true"
      @create="createConversation()"
      @select-agent="selectAgent"
      @switch="switchConversation"
      @rename="startRenameConversation"
      @delete="handleDeleteConversation"
      @logout="handleLogout"
      @load-more="loadMoreConversations"
    />

    <main
      class="chat-main"
      @paste="handleImagePaste"
      @dragover.prevent
      @drop.prevent="handleImageDrop"
    >
      <header class="top-actions">
        <button
          v-if="sidebarCollapsed"
          type="button"
          class="expand-button"
          title="展开侧栏"
          aria-label="展开侧栏"
          @click="sidebarCollapsed = false"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 5h14v14H5z" />
            <path d="M9 5v14" />
          </svg>
        </button>
        <template v-if="hasMessages">
          <n-button quaternary size="small">分享</n-button>
        </template>
      </header>

      <section v-if="!hasMessages" class="empty-state">
        <h1>准备好了，随时开始</h1>

        <ChatComposer v-model:prompt="prompt" v-bind="composerProps" v-on="composerListeners">
          <template #before-input>
            <TaskProgressPanel v-if="activeTaskProgress" :progress="activeTaskProgress" />
          </template>
        </ChatComposer>
      </section>

      <section v-else class="conversation-view" aria-label="聊天消息">
        <ChatMessageList
          :messages="currentMessages"
          :has-artifacts="currentArtifacts.length > 0"
          :reasoning-now="reasoningNow"
          @preview-attachment="previewMessageAttachment"
          @open-artifacts="openArtifactPanel()"
          @open-sources="workPanelOpen = true"
        />

        <footer class="conversation-composer">
          <TaskProgressPanel v-if="activeTaskProgress" :progress="activeTaskProgress" />

          <ChatComposer v-model:prompt="prompt" compact v-bind="composerProps" v-on="composerListeners" />
          <p>Agent Hub 也可能会犯错。请核查重要信息。</p>
        </footer>
      </section>

      <input
        ref="imageInputRef"
        type="file"
        accept=".jpg,.jpeg,.png,.webp,.gif,.doc,.docx,.xls,.xlsx,image/jpeg,image/png,image/webp,image/gif,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        multiple
        hidden
        @change="handleImageSelected"
      />
    </main>

    <ChatArtifactPanels
      :work-panel-open="workPanelOpen"
      :preview-open="artifactPreviewOpen"
      :artifacts="currentArtifacts"
      :selected-artifact="selectedArtifact"
      :loading="artifactsLoading"
      :preview-loading="artifactPreviewLoading"
      :downloading="artifactDownloading"
      :preview="artifactPreview"
      :preview-mode="currentArtifactPreviewMode"
      :preview-subtitle="artifactPreviewSubtitle"
      :language="currentArtifactLanguage"
      :highlighted-html="highlightedArtifactHtml"
      @close-panel="closeArtifactPanel"
      @close-preview="closeArtifactPreview"
      @select="selectArtifact"
      @download="downloadOneArtifact"
      @download-all="downloadAllConversationArtifacts"
    />

    <DeleteConversationModal
      v-model:show="deleteConfirmOpen"
      :deleting="deletingConversation"
      @cancel="cancelDeleteConversation"
      @confirm="confirmDeleteConversation"
    />

    <n-modal
      :show="!!imagePreviewUrl"
      to="body"
      transform-origin="center"
      @update:show="handleImagePreviewVisibleChange"
    >
      <div class="image-preview-modal">
        <img v-if="imagePreviewUrl" :src="imagePreviewUrl" alt="图片预览" />
      </div>
    </n-modal>

    <HistoryImagePickerModal
      v-model:show="historyImagePickerOpen"
      :attachments="reusableHistoryAttachments"
      :selected-ids="selectedReuseAttachmentIds"
      @toggle="toggleReuseAttachment"
    />

    <SkillPickerModal
      v-model:show="skillPickerOpen"
      :skills="availableSkills"
      :selected-ids="selectedSkillIds"
      :loading="skillsLoading"
      @toggle="toggleSelectedSkill"
    />

    <ThinkingConfigModal
      v-model:show="thinkingConfigOpen"
      v-model:selected-model="selectedModel"
      :model-options="modelOptions"
      :thinking-mode="thinkingMode"
      @select-mode="selectThinkingMode"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage, NButton, NModal } from 'naive-ui'

import {
  deleteConversation,
  fetchAvailableAgents,
  fetchAvailableModels,
  fetchAvailableSkills,
  fetchConversationMessages,
  fetchConversations,
  openChatStream,
  renameConversation,
  stopGeneration
} from '../api/chat'
import type {
  AvailableAgentRecord,
  AvailableModelRecord,
  AvailableSkillRecord,
  ChatAttachmentRecord,
  ChatMessageRecord,
  ChatTaskProgressRecord,
  ConversationRecord
} from '../api/chat'
import { useAuthStore } from '../stores/auth'
import {
  appendReasoningEvent,
  applyToolCallEvent,
  applyToolOutputEvent,
  applyToolResultEvent,
  createInitialProcessEvents,
  createProcessEvents
} from './chat-tool-events.helpers'
import type { ChatProcessEvent } from './chat-tool-events.helpers'
import {
  canClearAgentSelection,
  groupConversationsByRecency,
  mapConversationRecord,
  resolveAgentSelectionForConversation
} from './chat-agent.helpers'
import type { ConversationItem } from './chat-agent.helpers'
import {
  applyMessageCompletedIfStreaming,
  applyStreamAssistantMessageId,
  applyStreamConversationId,
  findStoppableAssistantMessage,
  resolveConversationSwitchStopAction,
  resolveNewConversationState,
  resolveSendButtonDisabled,
  shouldApplyStreamingEvent
} from './chat-view.helpers'
import type { NewConversationResetOptions } from './chat-view.helpers'
import { toBeijingIsoString } from '../utils/beijing-time'
import TaskProgressPanel from './TaskProgressPanel.vue'
import ChatArtifactPanels from './chat/ChatArtifactPanels.vue'
import ChatComposer from './chat/ChatComposer.vue'
import ChatMessageList from './chat/ChatMessageList.vue'
import ChatSidebar from './chat/ChatSidebar.vue'
import DeleteConversationModal from './chat/DeleteConversationModal.vue'
import HistoryImagePickerModal from './chat/HistoryImagePickerModal.vue'
import SkillPickerModal from './chat/SkillPickerModal.vue'
import ThinkingConfigModal from './chat/ThinkingConfigModal.vue'
import type {
  AgentOption,
  ChatAttachmentItem,
  ChatMessageItem,
  ThinkingMode,
  UploadMenuOption
} from './chat/chat-types'
import { useChatArtifacts } from './chat/useChatArtifacts'
import { useChatAttachments } from './chat/useChatAttachments'
import { useReasoningTimer } from './chat/useReasoningTimer'
import { useTaskProgress } from './chat/useTaskProgress'

const CONVERSATION_PAGE_SIZE = 20

const router = useRouter()
const message = useMessage()
const authStore = useAuthStore()
const sidebarRef = ref<InstanceType<typeof ChatSidebar> | null>(null)
const prompt = ref('')
const streaming = ref(false)
const thinkingConfigOpen = ref(false)
const deleteConfirmOpen = ref(false)
const deletingConversation = ref(false)
const sidebarCollapsed = ref(false)
const activeConversationId = ref<number | null>(null)
const activeAgentName = ref('通用 Agent')
const activeAgentKey = ref<string | null>(null)
const thinkingMode = ref<ThinkingMode>('instant')
const pendingDeleteConversationId = ref<number | null>(null)
const streamControllerRef = ref<AbortController | null>(null)
const imageInputRef = ref<HTMLInputElement | null>(null)
const skillPickerOpen = ref(false)
const skillsLoading = ref(false)
const selectedSkillIds = ref<number[]>([])
const selectedModel = ref<number | null>(null)
const availableModels = ref<AvailableModelRecord[]>([])
const availableSkills = ref<AvailableSkillRecord[]>([])
const conversationsLoading = ref(false)
const conversationHasMore = ref(true)
const agentOptions = ref<AgentOption[]>([])
const conversations = ref<ConversationItem[]>([])
const messageMap = ref<Record<number, ChatMessageItem[]>>({})

const currentMessages = computed(() =>
  activeConversationId.value ? messageMap.value[activeConversationId.value] ?? [] : []
)
const selectedModelItem = computed(() =>
  availableModels.value.find((item) => item.modelId === selectedModel.value) ?? null
)

const {
  pendingImages,
  imagePreviewUrl,
  historyImagePickerOpen,
  selectedReuseAttachmentIds,
  hasUploadingImage,
  hasFailedImage,
  reusableHistoryAttachments,
  selectedReuseAttachments,
  composerImageCount,
  composerAttachmentCount,
  historyImageReuseDisabledReason,
  handleImageSelected,
  handleImagePaste,
  handleImageDrop,
  openHistoryImagePicker,
  toggleReuseAttachment,
  removePendingImage,
  removeSelectedReuseAttachment,
  clearSelectedReuseAttachments,
  clearPendingImages,
  takeReadyAttachmentsForSend,
  revokeSentPreviewUrls,
  handleImagePreviewVisibleChange,
  previewImage,
  previewMessageAttachment
} = useChatAttachments({ selectedModelItem, currentMessages, notify: message })

const {
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
} = useChatArtifacts({ activeConversationId, notify: message })

const {
  activeTaskProgress,
  applyTaskProgress,
  activateTaskStreamContext,
  deactivateTaskStreamContext,
  registerTaskStreamConversationId,
  isCurrentTaskStream,
  markTaskProgressFailedForCurrentStream,
  markTaskProgressStoppedForCurrentStream,
  restoreActiveTaskProgress
} = useTaskProgress(activeConversationId)

const {
  reasoningNow,
  startReasoningTiming,
  finishReasoningTiming,
  syncReasoningTimer,
  disposeReasoningTimer
} = useReasoningTimer(currentMessages)

watch(activeAgentKey, (key) => {
  if (key) {
    selectedSkillIds.value = []
    skillPickerOpen.value = false
  }
})

const modelOptions = computed(() =>
  availableModels.value.map((item) => ({
    label: item.displayName,
    value: item.modelId
  }))
)

const selectedModelLabel = computed(() => {
  if (!selectedModel.value) {
    return '默认模型'
  }

  return availableModels.value.find((item) => item.modelId === selectedModel.value)?.displayName ?? '默认模型'
})

const selectedSkillItems = computed(() => {
  const selected = new Set(selectedSkillIds.value)
  return availableSkills.value.filter((item) => selected.has(item.skillId))
})
const uploadMenuOptions = computed(() => {
  const options: UploadMenuOption[] = [
    {
      key: 'upload-image',
      label: selectedModelItem.value?.imageInputSupported
        ? '上传照片和文件'
        : '上传文件',
      disabled: false
    },
    {
      key: 'reuse-image',
      label: historyImageReuseDisabledReason.value
        ? `选择历史图片（${historyImageReuseDisabledReason.value}）`
        : '选择历史图片',
      disabled: Boolean(historyImageReuseDisabledReason.value)
    }
  ]

  if (!activeAgentKey.value) {
    options.push({
      key: 'select-skill',
      label: selectedSkillIds.value.length ? `选择 Skill（已选 ${selectedSkillIds.value.length}）` : '选择 Skill',
      disabled: false
    })
  }

  return options
})
const firstReasoningModel = computed(() => availableModels.value.find((item) => item.reasoningSupported) ?? null)
const activeConversation = computed(() =>
  conversations.value.find((item) => item.id === activeConversationId.value) ?? null
)
const groupedConversations = computed(() => groupConversationsByRecency(conversations.value))
const activeAgentLocked = computed(() =>
  !canClearAgentSelection({
    activeConversation: activeConversation.value,
    messageCount: currentMessages.value.length
  })
)
const hasMessages = computed(() => currentMessages.value.length > 0)
const userInitial = computed(() => (authStore.displayName || '用户').trim().slice(0, 1).toUpperCase())
const canSubmitContent = computed(() => Boolean(prompt.value.trim()) || composerAttachmentCount.value > 0)
const sendButtonDisabled = computed(() =>
  resolveSendButtonDisabled({
    streaming: streaming.value,
    hasUploadingImage: hasUploadingImage.value,
    hasFailedImage: hasFailedImage.value,
    canSubmitContent: canSubmitContent.value,
    imageInputBlocked: composerImageCount.value > 0 && !selectedModelItem.value?.imageInputSupported
  })
)

// 空状态和会话底部各有一个输入框，两处共享同一份状态和事件处理。
const composerProps = computed(() => ({
  pendingImages: pendingImages.value,
  selectedReuseAttachments: selectedReuseAttachments.value,
  uploadMenuOptions: uploadMenuOptions.value,
  activeAgentKey: activeAgentKey.value,
  activeAgentName: activeAgentName.value,
  activeAgentLocked: activeAgentLocked.value,
  selectedSkillItems: selectedSkillItems.value,
  thinkingMode: thinkingMode.value,
  selectedModelLabel: selectedModelLabel.value,
  streaming: streaming.value,
  sendButtonDisabled: sendButtonDisabled.value
}))
const composerListeners = {
  send: handleSend,
  'preview-image': previewImage,
  'remove-pending': removePendingImage,
  'remove-reuse': removeSelectedReuseAttachment,
  'upload-menu-select': handleUploadMenuSelect,
  'clear-agent': clearAgent,
  'remove-skill': removeSelectedSkill,
  'select-thinking-mode': selectThinkingMode,
  'configure-thinking': () => {
    thinkingConfigOpen.value = true
  }
}

function mapConversation(item: ConversationRecord): ConversationItem {
  return mapConversationRecord(item)
}

function mapMessage(item: ChatMessageRecord): ChatMessageItem {
  return {
    id: item.messageId,
    role: item.role.toLowerCase() === 'user' ? 'user' : 'assistant',
    content: item.content,
    errorMessage: item.errorMessage ?? null,
    reasoningContent: item.reasoningContent ?? '',
    processExpanded: false,
    status: item.status,
    attachments: item.attachments?.map(mapAttachment) ?? [],
    processEvents: createInitialProcessEvents(item.reasoningContent, item.processEvents)
  }
}

function mapAttachment(item: ChatAttachmentRecord): ChatAttachmentItem {
  return {
    attachmentId: item.attachmentId,
    filename: item.filename,
    contentType: item.contentType,
    status: item.status,
    expired: item.expired,
    previewUrl: item.previewUrl ?? null,
    message: item.message ?? null,
    messageId: item.messageId ?? null
  }
}

async function loadConversations(options: { reset?: boolean } = {}) {
  if (conversationsLoading.value) {
    return
  }

  const reset = options.reset ?? true
  const offset = reset ? 0 : conversations.value.filter((conversation) => conversation.id > 0).length
  try {
    conversationsLoading.value = true
    const data = await fetchConversations({
      limit: CONVERSATION_PAGE_SIZE,
      offset
    })
    const nextConversations = data.items.map(mapConversation)
    conversations.value = reset ? nextConversations : mergeConversations(conversations.value, nextConversations)
    conversationHasMore.value = conversations.value.filter((conversation) => conversation.id > 0).length < data.total
    if (activeConversationId.value) {
      syncActiveAgentFromConversation()
    }

    if (!activeConversationId.value && conversations.value.length) {
      activeConversationId.value = conversations.value[0].id
      syncActiveAgentFromConversation(conversations.value[0])
      await loadMessages(conversations.value[0].id)
      await loadArtifacts(conversations.value[0].id)
      await restoreActiveTaskProgress(conversations.value[0].id)
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载会话失败')
  } finally {
    conversationsLoading.value = false
  }
}

function mergeConversations(current: ConversationItem[], next: ConversationItem[]) {
  const seen = new Set<number>()
  return [...current, ...next].filter((conversation) => {
    if (seen.has(conversation.id)) {
      return false
    }
    seen.add(conversation.id)
    return true
  })
}

function loadMoreConversations() {
  if (!conversationHasMore.value || conversationsLoading.value) {
    return
  }

  void loadConversations({ reset: false })
}

async function loadMessages(conversationId: number) {
  try {
    const data = await fetchConversationMessages(conversationId)
    messageMap.value[conversationId] = data.map(mapMessage)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载消息失败')
  }
}

async function loadModels() {
  try {
    availableModels.value = await fetchAvailableModels()
    selectedModel.value = availableModels.value.find((item) => item.defaultModel)?.modelId
      ?? availableModels.value[0]?.modelId
      ?? null
  } catch (error) {
    message.warning(error instanceof Error ? error.message : '加载模型列表失败，将使用默认模型')
  }
}

async function loadAgents() {
  try {
    agentOptions.value = (await fetchAvailableAgents()).map(toAgentOption)
    syncActiveAgentFromConversation()
  } catch (error) {
    message.warning(error instanceof Error ? error.message : '加载 Agent 列表失败')
  }
}

async function loadSkills() {
  skillsLoading.value = true
  try {
    availableSkills.value = await fetchAvailableSkills()
  } catch (error) {
    message.warning(error instanceof Error ? error.message : '加载 Skill 列表失败')
  } finally {
    skillsLoading.value = false
  }
}

function toAgentOption(item: AvailableAgentRecord): AgentOption {
  return {
    key: item.agentKey,
    name: item.name,
    shortName: item.shortName || item.name.slice(0, 1),
    description: item.description || '可配置 Agent'
  }
}

function ensureConversationMessages(conversationId: number) {
  if (!messageMap.value[conversationId]) {
    messageMap.value[conversationId] = []
  }

  return messageMap.value[conversationId]
}

function createConversation(options: NewConversationResetOptions = {}) {
  markCurrentAssistantStopped()
  closeStream()
  closeFloatingMenus()

  const nextState = resolveNewConversationState({
    activeConversationId: activeConversationId.value,
    activeAgentKey: activeAgentKey.value,
    activeAgentName: activeAgentName.value,
    selectedSkillIds: selectedSkillIds.value,
    prompt: prompt.value,
    workPanelOpen: workPanelOpen.value,
    artifactPreviewOpen: artifactPreviewOpen.value,
    artifactPreview: artifactPreview.value,
    selectedArtifactId: selectedArtifactId.value,
    skillPickerOpen: skillPickerOpen.value,
    historyImagePickerOpen: historyImagePickerOpen.value,
    thinkingConfigOpen: thinkingConfigOpen.value
  }, options)

  activeConversationId.value = nextState.activeConversationId
  activeTaskProgress.value = null
  activeAgentKey.value = nextState.activeAgentKey
  activeAgentName.value = nextState.activeAgentName
  selectedSkillIds.value = nextState.selectedSkillIds
  prompt.value = nextState.prompt
  workPanelOpen.value = nextState.workPanelOpen
  artifactPreviewOpen.value = nextState.artifactPreviewOpen
  artifactPreview.value = nextState.artifactPreview as typeof artifactPreview.value
  selectedArtifactId.value = nextState.selectedArtifactId
  skillPickerOpen.value = nextState.skillPickerOpen
  historyImagePickerOpen.value = nextState.historyImagePickerOpen
  thinkingConfigOpen.value = nextState.thinkingConfigOpen
  clearPendingImages()
  clearSelectedReuseAttachments()
}

async function switchConversation(conversationId: number) {
  if (activeConversationId.value === conversationId) {
    closeFloatingMenus()
    return
  }

  stopCurrentStreamBeforeLeavingConversation()
  deactivateTaskStreamContext()
  activeConversationId.value = conversationId
  activeTaskProgress.value = null
  closeFloatingMenus()
  prompt.value = ''
  clearPendingImages()
  clearSelectedReuseAttachments()
  artifactPreviewOpen.value = false
  artifactPreview.value = null
  syncActiveAgentFromConversation()
  await loadMessages(conversationId)
  await loadArtifacts(conversationId)
  await restoreActiveTaskProgress(conversationId)
}

function selectThinkingMode(mode: ThinkingMode) {
  if (mode === 'thinking' && !ensureThinkingModelSelected()) {
    return
  }
  thinkingMode.value = mode
}

function ensureThinkingModelSelected() {
  if (selectedModelItem.value?.reasoningSupported) {
    return true
  }

  if (firstReasoningModel.value) {
    selectedModel.value = firstReasoningModel.value.modelId
    message.info(`已切换到支持思考内容的模型：${firstReasoningModel.value.displayName}`)
    return true
  }

  if (!availableModels.value.length) {
    return true
  }

  thinkingMode.value = 'instant'
  message.warning('当前没有可用的思考模型，请先在管理端为模型开启“支持思考内容”')
  return false
}

function closeFloatingMenus() {
  sidebarRef.value?.closeMenus()
}

async function startRenameConversation(conversation: ConversationItem) {
  closeFloatingMenus()
  const nextTitle = window.prompt('重命名会话', conversation.title)?.trim()
  if (!nextTitle || nextTitle === conversation.title) {
    return
  }

  try {
    await renameConversation(conversation.id, nextTitle)
    conversations.value = conversations.value.map((item) =>
      item.id === conversation.id ? { ...item, title: nextTitle } : item
    )
    message.success('已重命名')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '重命名失败')
  }
}

function handleDeleteConversation(conversationId: number) {
  closeFloatingMenus()
  pendingDeleteConversationId.value = conversationId
  deleteConfirmOpen.value = true
}

function cancelDeleteConversation() {
  if (deletingConversation.value) {
    return
  }
  deleteConfirmOpen.value = false
  pendingDeleteConversationId.value = null
}

async function confirmDeleteConversation() {
  const conversationId = pendingDeleteConversationId.value
  if (!conversationId) {
    deleteConfirmOpen.value = false
    return
  }

  try {
    deletingConversation.value = true
    await deleteConversation(conversationId)
    conversations.value = conversations.value.filter((item) => item.id !== conversationId)
    delete messageMap.value[conversationId]
    forgetConversationArtifacts(conversationId)
    if (activeConversationId.value === conversationId) {
      deactivateTaskStreamContext()
      activeTaskProgress.value = null
      clearSelectedReuseAttachments()
      clearPendingImages()
      resetArtifactSelection()
      activeConversationId.value = conversations.value[0]?.id ?? null
      if (activeConversationId.value) {
        syncActiveAgentFromConversation()
        await loadMessages(activeConversationId.value)
        await loadArtifacts(activeConversationId.value)
        await restoreActiveTaskProgress(activeConversationId.value)
      } else {
        activeTaskProgress.value = null
        syncActiveAgentFromConversation(null)
      }
    }
    message.success('已删除')
    deleteConfirmOpen.value = false
    pendingDeleteConversationId.value = null
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除失败')
  } finally {
    deletingConversation.value = false
  }
}

function selectAgent(agent: AgentOption) {
  activeAgentKey.value = agent.key
  activeAgentName.value = agent.name
  selectedSkillIds.value = []
  createConversation({ preserveAgent: true })
}

function clearAgent() {
  if (activeAgentLocked.value) {
    message.warning('已有聊天记录的 Agent 会话不能移除 Agent')
    return
  }

  activeAgentKey.value = null
  activeAgentName.value = '通用 Agent'
}

function syncActiveAgentFromConversation(conversation: ConversationItem | null = activeConversation.value) {
  const selection = resolveAgentSelectionForConversation({
    conversation,
    agents: agentOptions.value,
    fallbackName: '通用 Agent'
  })
  activeAgentKey.value = selection.key
  activeAgentName.value = selection.name
  if (selection.key) {
    selectedSkillIds.value = []
  }
}

function handleUploadMenuSelect(key: string | number) {
  if (key === 'select-skill') {
    if (activeAgentKey.value) {
      message.info('当前 Agent 的 Skill 由后台绑定')
      return
    }
    skillPickerOpen.value = true
    if (!skillsLoading.value) {
      void loadSkills()
    }
    return
  }

  if (key === 'reuse-image') {
    openHistoryImagePicker()
    return
  }

  if (key !== 'upload-image') {
    return
  }

  imageInputRef.value?.click()
}

function removeSelectedSkill(skillId: number) {
  selectedSkillIds.value = selectedSkillIds.value.filter((item) => item !== skillId)
}

function toggleSelectedSkill(skillId: number) {
  if (selectedSkillIds.value.includes(skillId)) {
    removeSelectedSkill(skillId)
    return
  }

  selectedSkillIds.value = [...selectedSkillIds.value, skillId]
}

async function refreshActiveConversationMessages() {
  const conversationId = activeConversationId.value
  if (conversationId && conversationId > 0) {
    const existingProcessEvents = new Map(
      (messageMap.value[conversationId] ?? [])
        .filter((item) => item.processEvents?.length)
        .map((item) => [item.id, item.processEvents as ChatProcessEvent[]])
    )
    const data = await fetchConversationMessages(conversationId)
    messageMap.value[conversationId] = data.map((item) => {
      const mapped = mapMessage(item)
      mapped.processEvents = existingProcessEvents.get(mapped.id) ?? mapped.processEvents
      return mapped
    })
  }
}

function closeStream() {
  streamControllerRef.value?.abort()
  streamControllerRef.value = null
  streaming.value = false
  deactivateTaskStreamContext()
  syncReasoningTimer()
}

function requestStopGeneration(messageId: number) {
  return stopGeneration(messageId).catch((error) => {
    message.error(error instanceof Error ? error.message : '中止生成失败')
  })
}

function refreshAfterStopping() {
  void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
  void refreshActiveArtifacts()
  void loadConversations()
}

function stopCurrentStream() {
  const assistantMessage = findStoppableAssistantMessage(currentMessages.value)
  markTaskProgressStoppedForCurrentStream('已中止生成')
  deactivateTaskStreamContext()
  if (assistantMessage && assistantMessage.id > 0) {
    markCurrentAssistantStopped()
    void requestStopGeneration(assistantMessage.id).finally(() => {
      closeStream()
      refreshAfterStopping()
    })
    return
  }

  markCurrentAssistantStopped()
  closeStream()
  refreshAfterStopping()
}

function stopCurrentStreamBeforeLeavingConversation() {
  const assistantMessage = findStoppableAssistantMessage(currentMessages.value)
  const stopAction = resolveConversationSwitchStopAction({
    streaming: streaming.value,
    assistantMessageId: assistantMessage?.id
  })

  if (!stopAction.shouldStop) {
    return
  }

  markTaskProgressStoppedForCurrentStream('已切换会话，已中止生成')
  deactivateTaskStreamContext()
  markCurrentAssistantStopped('已切换会话，已中止生成')
  closeStream()
  if (stopAction.messageId) {
    void requestStopGeneration(stopAction.messageId)
  }
}

function markCurrentAssistantStopped(errorMessage?: string) {
  if (!streaming.value) {
    return
  }

  const assistantMessage = findStoppableAssistantMessage(currentMessages.value)
  if (assistantMessage) {
    finishReasoningTiming(assistantMessage)
    assistantMessage.status = 'STOPPED'
    assistantMessage.errorMessage = errorMessage ?? null
  }
}

function markAssistantFailed(assistantMessage?: ChatMessageItem, errorMessage?: string) {
  if (!assistantMessage) {
    return
  }

  finishReasoningTiming(assistantMessage)
  assistantMessage.status = 'FAILED'
  assistantMessage.errorMessage = errorMessage ?? null
}

function handleSend() {
  const trimmedPrompt = prompt.value.trim()

  if (streaming.value) {
    stopCurrentStream()
    return
  }

  if (hasUploadingImage.value) {
    message.warning('图片仍在上传中')
    return
  }

  if (hasFailedImage.value) {
    message.warning('请先移除上传失败的图片')
    return
  }

  if (composerImageCount.value > 0 && !selectedModelItem.value?.imageInputSupported) {
    message.warning('当前模型不支持图片输入')
    return
  }

  if (!trimmedPrompt && composerAttachmentCount.value === 0) {
    return
  }

  if (thinkingMode.value === 'thinking' && !ensureThinkingModelSelected()) {
    return
  }

  closeStream()
  activeTaskProgress.value = null

  if (!activeConversationId.value) {
    const nextId = -Date.now()
    conversations.value.unshift({
      id: nextId,
      title: '新聊天',
      updatedAt: toBeijingIsoString(Date.now()),
      agentKey: activeAgentKey.value
    })
    messageMap.value[nextId] = []
    activeConversationId.value = nextId
  }

  const conversationId = activeConversationId.value
  const messages = ensureConversationMessages(conversationId)
  const assistantMessageId = -Date.now()
  let currentAssistantMessageId = assistantMessageId
  const { readyImages, reuseAttachments } = takeReadyAttachmentsForSend()
  const currentPendingAttachments: ChatAttachmentItem[] = readyImages.map((image) => ({
    attachmentId: image.attachmentId as number,
    filename: image.file.name,
    contentType: image.contentType,
    status: 'READY',
    expired: false,
    previewUrl: image.previewUrl,
    message: null,
    messageId: null
  }))

  messages.push({
    id: assistantMessageId - 1,
    role: 'user',
    content: trimmedPrompt,
    attachments: [...currentPendingAttachments, ...reuseAttachments]
  })

  messages.push({
    id: assistantMessageId,
    role: 'assistant',
    content: '',
    reasoningContent: '',
    processExpanded: false,
    reasoningStartedAt: undefined,
    reasoningFinishedAt: undefined,
    status: 'STREAMING',
    processEvents: createProcessEvents()
  })

  const currentPrompt = trimmedPrompt
  const currentThinkingEnabled = thinkingMode.value === 'thinking'
  const currentAttachmentIds = readyImages.map((image) => image.attachmentId as number)
  const currentReuseAttachmentIds = reuseAttachments.map((attachment) => attachment.attachmentId)
  const currentSkillIds = activeAgentKey.value ? [] : [...selectedSkillIds.value]
  prompt.value = ''
  streaming.value = true

  const applyStreamAssistantMessageIdFromEvent = (messageId?: number | null) => {
    currentAssistantMessageId = applyStreamAssistantMessageId(messages, currentAssistantMessageId, messageId)
  }
  const applyStreamConversationIdFromEvent = (streamConversationId?: number | null) => {
    registerTaskStreamConversationId(streamConversationId, taskStreamToken)
    activeConversationId.value = applyStreamConversationId(
      messageMap.value,
      activeConversationId.value,
      streamConversationId
    )
  }
  const taskStreamToken = activateTaskStreamContext(conversationId)
  const guardedApplyTaskProgress = (progress: ChatTaskProgressRecord) => {
    if (isCurrentTaskStream(taskStreamToken)) {
      applyTaskProgress(progress)
    }
  }

  streamControllerRef.value = openChatStream(
    {
      prompt: currentPrompt,
      token: authStore.token,
      conversationId: conversationId > 0 ? conversationId : null,
      modelId: selectedModel.value,
      agentKey: activeAgentKey.value,
      thinkingEnabled: currentThinkingEnabled,
      attachmentIds: currentAttachmentIds,
      reuseAttachmentIds: currentReuseAttachmentIds,
      skillIds: currentSkillIds
    },
    {
      onMeta: (data) => {
        if (data.protocol !== 'chat.sse.v1') {
          message.warning(`聊天流协议版本不兼容：${data.protocol}`)
        }
        applyStreamConversationIdFromEvent(data.conversationId)
        applyStreamAssistantMessageIdFromEvent(data.messageId)
      },
      onConversation: (data) => {
        const previousId = activeConversationId.value
        applyStreamConversationIdFromEvent(data.conversationId)

        conversations.value = [
          {
            id: data.conversationId,
            title: data.title,
            updatedAt: toBeijingIsoString(Date.now()),
            agentKey: activeAgentKey.value
          },
          ...conversations.value.filter((item) =>
            item.id !== data.conversationId && item.id !== previousId && item.id !== conversationId
          )
        ]
      },
      onMessage: (data) => {
        applyStreamAssistantMessageIdFromEvent(data.messageId)
      },
      onReasoningDelta: (data) => {
        if (!currentThinkingEnabled) {
          return
        }
        const assistantMessage = findAssistantMessage(currentAssistantMessageId)
        if (shouldApplyStreamingEvent(assistantMessage)) {
          startReasoningTiming(assistantMessage)
          assistantMessage.reasoningContent = `${assistantMessage.reasoningContent ?? ''}${data.content}`
          appendReasoningEvent(ensureProcessEvents(assistantMessage), data.content)
        }
      },
      onAnswerDelta: (data) => {
        const assistantMessage = findAssistantMessage(currentAssistantMessageId)
        if (shouldApplyStreamingEvent(assistantMessage)) {
          assistantMessage.content += data.content
        }
      },
      onToolCall: (data) => {
        const assistantMessage = findAssistantMessage(currentAssistantMessageId)
        if (shouldApplyStreamingEvent(assistantMessage)) {
          applyToolCallEvent(ensureProcessEvents(assistantMessage), data)
        }
      },
      onToolOutput: (data) => {
        const assistantMessage = findAssistantMessage(currentAssistantMessageId)
        if (shouldApplyStreamingEvent(assistantMessage)) {
          applyToolOutputEvent(ensureProcessEvents(assistantMessage), data)
        }
      },
      onToolResult: (data) => {
        const assistantMessage = findAssistantMessage(currentAssistantMessageId)
        if (shouldApplyStreamingEvent(assistantMessage)) {
          applyToolResultEvent(ensureProcessEvents(assistantMessage), data)
        }
      },
      onTaskProgressStarted: guardedApplyTaskProgress,
      onTaskProgressUpdated: guardedApplyTaskProgress,
      onTaskStepStarted: guardedApplyTaskProgress,
      onTaskStepCompleted: guardedApplyTaskProgress,
      onTaskStepFailed: guardedApplyTaskProgress,
      onTaskPlanRevised: guardedApplyTaskProgress,
      onTaskProgressCompleted: guardedApplyTaskProgress,
      onMessageCompleted: (data) => {
        const assistantMessage = findAssistantMessage(data.messageId ?? currentAssistantMessageId)
        if (applyMessageCompletedIfStreaming(assistantMessage, data.status)) {
          finishReasoningTiming(assistantMessage)
        }
      },
      onError: (data) => {
        const assistantMessage = findAssistantMessage(data.messageId ?? currentAssistantMessageId)
        if (shouldApplyStreamingEvent(assistantMessage)) {
          markAssistantFailed(assistantMessage, data.message ?? '流式响应失败')
        }
        markTaskProgressFailedForCurrentStream(data.message ?? '流式响应失败', taskStreamToken)
        message.error(data.message ?? '流式响应失败')
        closeStream()
        void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
        void refreshActiveArtifacts()
      },
      onDone: () => {
        finishReasoningTiming(findAssistantMessage(currentAssistantMessageId))
        closeStream()
        void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
        void refreshActiveArtifacts()
        void loadConversations()
      },
      onTransportError: () => {
        markCurrentAssistantStopped('SSE 连接已断开，正在同步状态')
        markTaskProgressStoppedForCurrentStream('SSE 连接已断开，正在同步状态', taskStreamToken)
        closeStream()
        void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
        void refreshActiveArtifacts()
      }
    }
  )
}

function findAssistantMessage(messageId: number) {
  if (!activeConversationId.value) {
    return undefined
  }

  return ensureConversationMessages(activeConversationId.value).find((item) => item.id === messageId)
}

function ensureProcessEvents(messageItem: ChatMessageItem) {
  if (!messageItem.processEvents) {
    messageItem.processEvents = createProcessEvents()
  }
  return messageItem.processEvents
}

function handleLogout() {
  closeStream()
  authStore.logout()
  void router.push('/login')
}

onBeforeUnmount(() => {
  closeStream()
  clearPendingImages()
  revokeSentPreviewUrls()
  disposeReasoningTimer()
})

onMounted(() => {
  void loadAgents()
  void loadModels()
  void loadSkills()
  void loadConversations()
})
</script>

<style scoped src="./chat/chat-controls.css"></style>

<style scoped>
:global(html),
:global(body) {
  overflow-x: hidden;
}

.chat-shell {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  height: 100vh;
  overflow-x: hidden;
  background: #ffffff;
  color: #111827;
  transition: grid-template-columns 0.25s ease;
}

.chat-shell.panel-open {
  grid-template-columns: 240px minmax(0, 1fr) 340px;
}

.chat-shell.preview-open {
  grid-template-columns: 240px minmax(0, 1fr) minmax(420px, 48vw);
}

.chat-shell.panel-open.preview-open {
  grid-template-columns: 240px minmax(320px, 1fr) 340px minmax(420px, 48vw);
}

.chat-shell.sidebar-collapsed {
  grid-template-columns: 0px minmax(0, 1fr);
}

.chat-shell.sidebar-collapsed.panel-open {
  grid-template-columns: 0px minmax(0, 1fr) 340px;
}

.chat-shell.sidebar-collapsed.preview-open {
  grid-template-columns: 0px minmax(0, 1fr) minmax(420px, 48vw);
}

.chat-shell.sidebar-collapsed.panel-open.preview-open {
  grid-template-columns: 0px minmax(320px, 1fr) 340px minmax(420px, 48vw);
}

.chat-shell.sidebar-collapsed .sidebar {
  padding: 0;
  border-right: 0;
  visibility: hidden;
  pointer-events: none;
}

.expand-button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #ffffff;
  color: #6b7280;
  cursor: pointer;
  margin-right: auto;
}

.expand-button:hover {
  background: #f3f4f6;
  border-color: #d1d5db;
  color: #1a1a2e;
}

.chat-main {
  --chat-column-width: 770px;
  position: relative;
  min-width: 0;
  height: 100vh;
  overflow: hidden;
}

.top-actions {
  position: absolute;
  top: 16px;
  left: 18px;
  right: 18px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-end;
}

.top-actions .expand-button {
  margin-right: auto;
}

.empty-state {
  display: grid;
  align-content: center;
  justify-items: center;
  height: 100vh;
  padding: 24px;
}

.empty-state h1 {
  margin: 0 0 48px;
  color: #111827;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.04em;
}

.image-preview-modal {
  display: grid;
  place-items: center;
  max-width: min(92vw, 980px);
  max-height: 86vh;
}

.image-preview-modal img {
  display: block;
  max-width: min(92vw, 980px);
  max-height: 86vh;
  border-radius: 8px;
  object-fit: contain;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.3);
}

.conversation-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow-y: auto;
  overflow-x: hidden;
}

.conversation-composer {
  position: sticky;
  bottom: 0;
  display: grid;
  justify-items: center;
  padding: 18px 24px 8px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), #ffffff 24%);
}

.conversation-composer p {
  width: min(100%, var(--chat-column-width));
  margin: 8px 0 0;
  color: #6b7280;
  font-size: 12px;
  text-align: center;
}

.conversation-view {
  scrollbar-width: thin;
  scrollbar-color: #cfd4dc transparent;
}

.conversation-view::-webkit-scrollbar {
  width: 8px;
}

.conversation-view::-webkit-scrollbar-track {
  background: transparent;
}

.conversation-view::-webkit-scrollbar-thumb {
  border: 2px solid transparent;
  border-radius: 999px;
  background: #cfd4dc;
  background-clip: content-box;
}

.conversation-view::-webkit-scrollbar-thumb:hover {
  background: #c6ccd6;
  background-clip: content-box;
}

@media (max-width: 1020px) {
  .chat-shell.panel-open,
  .chat-shell.preview-open,
  .chat-shell.panel-open.preview-open,
  .chat-shell {
    grid-template-columns: 240px minmax(0, 1fr);
  }

  .chat-shell.sidebar-collapsed,
  .chat-shell.sidebar-collapsed.preview-open,
  .chat-shell.sidebar-collapsed.panel-open.preview-open {
    grid-template-columns: 0px minmax(0, 1fr);
  }

  .chat-shell.sidebar-collapsed.panel-open {
    grid-template-columns: 0px minmax(0, 1fr);
  }
}

@media (max-width: 640px) {
  .chat-shell,
  .chat-shell.panel-open,
  .chat-shell.preview-open,
  .chat-shell.panel-open.preview-open {
    grid-template-columns: 1fr;
  }

  .chat-shell.sidebar-collapsed,
  .chat-shell.sidebar-collapsed.preview-open,
  .chat-shell.sidebar-collapsed.panel-open.preview-open {
    grid-template-columns: 1fr;
  }
}
</style>
