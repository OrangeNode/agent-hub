<template>
  <div class="chat-shell" :class="{ 'panel-open': workPanelOpen, 'sidebar-collapsed': sidebarCollapsed }" @click="openConversationMenuId = null">
    <aside class="sidebar">
      <div class="sidebar-header">
        <strong>Agent Hub</strong>
        <button type="button" class="icon-button" title="折叠侧栏" aria-label="折叠侧栏" @click="sidebarCollapsed = true">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 5h14v14H5z" />
            <path d="M9 5v14" />
          </svg>
        </button>
      </div>

      <nav class="primary-nav" aria-label="主导航">
        <button type="button" class="nav-item" @click="createConversation">
          <span class="nav-icon">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </span>
          新聊天
        </button>

        <div
          class="agent-menu-wrap"
          :class="{ open: agentMenuOpen }"
          @mouseenter="agentMenuOpen = true"
          @mouseleave="agentMenuOpen = false"
        >
          <button type="button" class="nav-item agent-trigger" @click="agentMenuOpen = !agentMenuOpen">
            <span class="nav-icon">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M7 8h10M7 12h10M7 16h6" />
                <path d="M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" />
              </svg>
            </span>
            更多 Agent
            <span class="chevron">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="m9 6 6 6-6 6" />
              </svg>
            </span>
          </button>

          <div class="agent-menu" role="menu">
            <button
              v-for="agent in agentOptions"
              :key="agent.key"
              type="button"
              class="agent-option"
              role="menuitem"
              @click="selectAgent(agent)"
            >
              <span class="agent-avatar">{{ agent.shortName }}</span>
              <span>
                <strong>{{ agent.name }}</strong>
                <small>{{ agent.description }}</small>
              </span>
            </button>
          </div>
        </div>
      </nav>

      <section class="recent-section">
        <h2>最近</h2>
        <div
          v-for="conversation in conversations"
          :key="conversation.id"
          class="recent-item"
          :class="{ active: conversation.id === activeConversationId }"
        >
          <button type="button" class="recent-title" @click="switchConversation(conversation.id)">
            {{ conversation.title }}
          </button>
          <div class="recent-actions">
            <button
              type="button"
              class="recent-more"
              aria-label="更多操作"
              @click.stop="toggleConversationMenu(conversation.id, $event)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 12h.01M19 12h.01M5 12h.01" />
              </svg>
            </button>
          </div>
        </div>
      </section>

      <div class="sidebar-footer">
        <div class="user-avatar">{{ userInitial }}</div>
        <div class="user-meta">
          <strong>{{ authStore.displayName || '用户' }}</strong>
        </div>
        <n-button quaternary size="small" @click="handleLogout">退出</n-button>
      </div>
    </aside>

    <div
      v-if="activeMenuConversation"
      class="conversation-action-menu"
      role="menu"
      :style="{ top: `${conversationMenuPosition.top}px`, left: `${conversationMenuPosition.left}px` }"
      @click.stop
    >
      <button type="button" role="menuitem" @click.stop="startRenameConversation(activeMenuConversation)">重命名</button>
      <button type="button" role="menuitem" class="danger" @click.stop="handleDeleteConversation(activeMenuConversation.id)">
        删除
      </button>
    </div>

    <main class="chat-main">
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
          <n-button quaternary size="small" @click="workPanelOpen = !workPanelOpen">
            {{ workPanelOpen ? '隐藏面板' : '工作面板' }}
          </n-button>
        </template>
      </header>

      <section v-if="!hasMessages" class="empty-state">
        <h1>准备好了，随时开始</h1>

        <div
          v-if="composerImageCount"
          class="pending-image-list"
          :class="{ single: composerImageCount === 1 }"
        >
          <div
            v-for="image in pendingImages"
            :key="image.localId"
            class="pending-image-item"
          >
            <button type="button" class="image-preview-button" @click="imagePreviewUrl = image.previewUrl">
              <img :src="image.previewUrl" :alt="image.file.name" />
              <span v-if="image.status === 'uploading'" class="image-progress">{{ image.progress }}%</span>
              <span v-if="image.status === 'failed'" class="image-error">上传失败</span>
            </button>
            <button
              type="button"
              class="image-remove-button"
              aria-label="移除图片"
              @click.stop="removePendingImage(image.localId)"
            >
              ×
            </button>
          </div>
          <div
            v-for="attachment in selectedReuseAttachments"
            :key="`reuse-${attachment.attachmentId}`"
            class="pending-image-item reused"
          >
            <button
              type="button"
              class="image-preview-button"
              @click="imagePreviewUrl = attachment.previewUrl ?? null"
            >
              <img :src="attachment.previewUrl ?? ''" :alt="attachment.filename || '历史图片'" />
              <span class="image-reuse-badge">已选</span>
            </button>
            <button
              type="button"
              class="image-remove-button"
              aria-label="移除历史图片"
              @click.stop="removeSelectedReuseAttachment(attachment.attachmentId)"
            >
              ×
            </button>
          </div>
        </div>

        <div class="landing-composer" :class="{ 'is-multiline': isMultiline }">
          <n-dropdown
            trigger="click"
            placement="top-start"
            :options="uploadMenuOptions"
            @select="handleUploadMenuSelect"
          >
            <button type="button" class="round-action composer-upload" aria-label="添加附件">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
            </button>
          </n-dropdown>
          <span v-if="activeAgentKey" class="agent-tag composer-agent">
            {{ activeAgentName }}
            <button v-if="!activeAgentLocked" type="button" class="agent-tag-close" @click="clearAgent">&times;</button>
          </span>
          <n-input
            v-model:value="prompt"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 5 }"
            placeholder="有问题，尽管问"
            @keydown.enter.exact.prevent="handleSend"
            class="composer-input"
          />
          <ThinkingModeSelect
            class="composer-model"
            :mode="thinkingMode"
            :model-label="selectedModelLabel"
            @select="selectThinkingMode"
            @configure="thinkingConfigOpen = true"
          />
          <button type="button" class="send-orb composer-send" :disabled="sendDisabled" @click="handleSend" aria-label="发送">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </button>
        </div>
      </section>

      <section v-else class="conversation-view" aria-label="聊天消息">
        <div class="message-list">
          <article
            v-for="messageItem in currentMessages"
            :key="messageItem.id"
            class="message-row"
            :class="messageItem.role"
          >
            <div v-if="messageItem.role === 'user'" class="user-bubble">
              <p v-if="messageItem.content" class="user-message-text">{{ messageItem.content }}</p>
              <div v-if="messageItem.attachments?.length" class="message-attachments">
                <button
                  v-for="attachment in messageItem.attachments"
                  :key="attachment.attachmentId"
                  type="button"
                  class="message-attachment"
                  :class="{ disabled: !canPreviewAttachment(attachment) }"
                  :disabled="!canPreviewAttachment(attachment)"
                  @click="previewMessageAttachment(attachment)"
                >
                  <img
                    v-if="canPreviewAttachment(attachment)"
                    :src="attachment.previewUrl ?? ''"
                    :alt="attachment.filename || '历史图片'"
                  />
                  <span v-else>{{ attachment.message || '图片已过期' }}</span>
                </button>
              </div>
            </div>

            <div v-else class="assistant-message">
              <div
                v-if="messageItem.reasoningContent"
                class="reasoning-block"
                :class="{ collapsed: !messageItem.reasoningExpanded }"
              >
                <button
                  type="button"
                  class="reasoning-toggle"
                  :aria-expanded="messageItem.reasoningExpanded"
                  @click="toggleReasoning(messageItem)"
                >
                  <span>{{ reasoningStatusText(messageItem) }}</span>
                  <span class="reasoning-caret" aria-hidden="true" />
                </button>
                <p v-if="messageItem.reasoningExpanded" class="reasoning-content">
                  {{ messageItem.reasoningContent }}
                </p>
              </div>
              <div
                class="assistant-markdown"
                v-html="renderAssistantMarkdown(messageItem.content || '正在生成回复...')"
              />
              <div class="message-tools">
                <button type="button" title="复制" @click="copyMessage(messageItem.content)">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                  </svg>
                  复制
                </button>
                <button type="button" title="赞">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M7 22V11l-5 0v11h5zm2-11l2.5-5.5a1.5 1.5 0 0 1 2.9.3L14 8h5.3a1.5 1.5 0 0 1 1.5 1.7l-1.5 9A1.5 1.5 0 0 1 17.8 20H9V11z" />
                  </svg>
                  赞
                </button>
                <button type="button" title="踩">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M17 2v11l5 0V2h-5zm-2 11l-2.5 5.5a1.5 1.5 0 0 1-2.9-.3L10 16H4.7a1.5 1.5 0 0 1-1.5-1.7l1.5-9A1.5 1.5 0 0 1 6.2 4H15v9z" />
                  </svg>
                  踩
                </button>
                <button type="button" title="重新生成">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M1 4v6h6" />
                    <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
                  </svg>
                  重新生成
                </button>
                <button type="button" title="来源" @click="workPanelOpen = true">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                    <polyline points="14 2 14 8 20 8" />
                    <line x1="16" y1="13" x2="8" y2="13" />
                    <line x1="16" y1="17" x2="8" y2="17" />
                    <polyline points="10 9 9 9 8 9" />
                  </svg>
                  来源
                </button>
              </div>
            </div>
          </article>
        </div>

        <footer class="conversation-composer">
          <div
            v-if="composerImageCount"
            class="pending-image-list"
            :class="{ single: composerImageCount === 1 }"
          >
            <div
              v-for="image in pendingImages"
              :key="image.localId"
              class="pending-image-item"
            >
              <button type="button" class="image-preview-button" @click="imagePreviewUrl = image.previewUrl">
                <img :src="image.previewUrl" :alt="image.file.name" />
                <span v-if="image.status === 'uploading'" class="image-progress">{{ image.progress }}%</span>
                <span v-if="image.status === 'failed'" class="image-error">上传失败</span>
              </button>
              <button
                type="button"
                class="image-remove-button"
                aria-label="移除图片"
                @click.stop="removePendingImage(image.localId)"
              >
                ×
              </button>
            </div>
            <div
              v-for="attachment in selectedReuseAttachments"
              :key="`reuse-${attachment.attachmentId}`"
              class="pending-image-item reused"
            >
              <button
                type="button"
                class="image-preview-button"
                @click="imagePreviewUrl = attachment.previewUrl ?? null"
              >
                <img :src="attachment.previewUrl ?? ''" :alt="attachment.filename || '历史图片'" />
                <span class="image-reuse-badge">已选</span>
              </button>
              <button
                type="button"
                class="image-remove-button"
                aria-label="移除历史图片"
                @click.stop="removeSelectedReuseAttachment(attachment.attachmentId)"
              >
                ×
              </button>
            </div>
          </div>
          <div class="landing-composer compact" :class="{ 'is-multiline': isMultiline }">
            <n-dropdown
              trigger="click"
              placement="top-start"
              :options="uploadMenuOptions"
              @select="handleUploadMenuSelect"
            >
              <button type="button" class="round-action composer-upload" aria-label="添加附件">
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 5v14M5 12h14" />
                </svg>
              </button>
            </n-dropdown>
            <span v-if="activeAgentKey" class="agent-tag composer-agent">
              {{ activeAgentName }}
              <button v-if="!activeAgentLocked" type="button" class="agent-tag-close" @click="clearAgent">&times;</button>
            </span>
            <n-input
              v-model:value="prompt"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 5 }"
              placeholder="有问题，尽管问"
              @keydown.enter.exact.prevent="handleSend"
              class="composer-input"
            />
            <ThinkingModeSelect
              class="composer-model"
              :mode="thinkingMode"
              :model-label="selectedModelLabel"
              @select="selectThinkingMode"
              @configure="thinkingConfigOpen = true"
            />
            <button type="button" class="send-orb composer-send" :disabled="sendDisabled" @click="handleSend" aria-label="发送">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M5 12h14M13 6l6 6-6 6" />
              </svg>
            </button>
          </div>
          <p>Agent Hub 也可能会犯错。请核查重要信息。</p>
        </footer>
      </section>

      <input
        ref="imageInputRef"
        type="file"
        accept=".jpg,.jpeg,.png,.webp,.gif,image/jpeg,image/png,image/webp,image/gif"
        multiple
        hidden
        @change="handleImageSelected"
      />
    </main>

    <aside v-if="workPanelOpen" class="work-panel" aria-label="工作面板">
      <div class="work-panel-header">
        <div>
          <strong>工作面板</strong>
          <span>任务进度与预览插槽</span>
        </div>
        <n-button quaternary size="small" @click="workPanelOpen = false">收起</n-button>
      </div>

      <section class="panel-card">
        <div class="panel-title">
          <span>任务进度</span>
          <small>Todo list</small>
        </div>
        <label v-for="todo in todos" :key="todo.id" class="todo-item">
          <input type="checkbox" :checked="todo.done" disabled />
          <span>{{ todo.title }}</span>
        </label>
      </section>

      <section class="panel-card preview-card">
        <div class="panel-title">
          <span>预览插槽</span>
          <small>Excel / Word / PDF / Markdown</small>
        </div>
        <div class="preview-placeholder">
          <div class="file-stack">
            <span>XLSX</span>
            <span>DOCX</span>
            <span>PDF</span>
            <span>MD</span>
          </div>
          <p>后续文件预览组件会挂载在这里，不挤占主聊天区。</p>
        </div>
      </section>
    </aside>

    <n-modal
      v-model:show="deleteConfirmOpen"
      to="body"
      transform-origin="center"
      :mask-closable="!deletingConversation"
      :close-on-esc="!deletingConversation"
    >
      <section class="delete-conversation-modal" role="dialog" aria-modal="true" aria-labelledby="delete-conversation-title">
        <header class="delete-modal-header">
          <h3 id="delete-conversation-title">删除会话</h3>
        </header>
        <div class="delete-modal-body">
          <p>删除后无法恢复，确认删除这个会话吗？</p>
        </div>
        <div class="delete-modal-footer">
          <n-button :disabled="deletingConversation" @click="cancelDeleteConversation">取消</n-button>
          <n-button type="error" :loading="deletingConversation" @click="confirmDeleteConversation">删除</n-button>
        </div>
      </section>
    </n-modal>

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

    <n-modal v-model:show="historyImagePickerOpen" to="body" transform-origin="center">
      <section class="history-image-modal" role="dialog" aria-modal="true" aria-labelledby="history-image-title">
        <header class="history-image-header">
          <div>
            <h3 id="history-image-title">选择历史图片</h3>
            <span>已选 {{ selectedReuseAttachmentIds.length }} 张</span>
          </div>
          <n-button quaternary size="small" @click="historyImagePickerOpen = false">完成</n-button>
        </header>
        <div class="history-image-grid">
          <button
            v-for="attachment in reusableHistoryAttachments"
            :key="attachment.attachmentId"
            type="button"
            class="history-image-option"
            :class="{ selected: isReuseAttachmentSelected(attachment.attachmentId) }"
            @click="toggleReuseAttachment(attachment.attachmentId)"
          >
            <img :src="attachment.previewUrl ?? ''" :alt="attachment.filename || '历史图片'" />
            <span v-if="isReuseAttachmentSelected(attachment.attachmentId)">已选择</span>
          </button>
        </div>
      </section>
    </n-modal>

    <n-modal
      v-model:show="thinkingConfigOpen"
      preset="card"
      class="thinking-config-modal"
      title="思考时长"
      style="width: min(320px, calc(100vw - 32px))"
    >
      <div class="config-row">
        <span>模型</span>
        <n-select v-model:value="selectedModel" :options="modelOptions" size="small" class="config-select" />
      </div>
      <div class="config-divider" />
      <button type="button" class="mode-option" :class="{ active: thinkingMode === 'instant' }" @click="selectThinkingMode('instant')">
        <strong>Instant</strong>
        <span>适用于日常聊天</span>
      </button>
      <button type="button" class="mode-option" :class="{ active: thinkingMode === 'thinking' }" @click="selectThinkingMode('thinking')">
        <strong>Thinking</strong>
        <span>适用于解答复杂问题</span>
      </button>
      <p>思考越深入，回答越详尽，但生成时间也会相应增加。</p>
    </n-modal>
  </div>
</template>

<script lang="ts">
export interface ReusableImageAttachmentLike {
  attachmentId: number
  expired: boolean
  previewUrl?: string | null
}

export function selectReusableImageAttachments<T extends ReusableImageAttachmentLike>(attachments: T[]) {
  return attachments.filter((item) => !item.expired && Boolean(item.previewUrl))
}

export function validateReuseImageCount(pendingCount: number, reuseCount: number) {
  if (pendingCount + reuseCount > 8) {
    return '单条消息最多上传 8 张图片'
  }

  return null
}
</script>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage, NButton, NDropdown, NInput, NModal, NSelect } from 'naive-ui'

import {
  confirmImageUpload,
  createImageUploadUrl,
  deleteConversation,
  fetchAvailableAgents,
  fetchAvailableModels,
  fetchConversationMessages,
  fetchConversations,
  openChatStream,
  renameConversation
} from '../api/chat'
import type {
  AvailableAgentRecord,
  AvailableModelRecord,
  ChatAttachmentRecord,
  ChatMessageRecord,
  ConversationRecord
} from '../api/chat'
import { useAuthStore } from '../stores/auth'
import { renderAssistantMarkdown } from './message-render.helpers'
import { buildReasoningStatusText } from './reasoning-timer.helpers'
import { validateImageCount, validateImageFile } from './chat-attachments.helpers'
import { updatePendingImageByLocalId } from './chat-pending-images.helpers'
import {
  canClearAgentSelection,
  mapConversationRecord,
  resolveAgentSelectionForConversation
} from './chat-agent.helpers'
import type { ConversationItem } from './chat-agent.helpers'
import ThinkingModeSelect from './ThinkingModeSelect.vue'

type ChatRole = 'user' | 'assistant'
type ThinkingMode = 'instant' | 'thinking'

interface ChatMessageItem {
  id: number
  role: ChatRole
  content: string
  reasoningContent?: string
  reasoningExpanded?: boolean
  reasoningStartedAt?: number
  reasoningFinishedAt?: number
  status?: string
  attachments?: ChatAttachmentItem[]
}

interface ChatAttachmentItem {
  attachmentId: number
  filename?: string | null
  contentType?: string | null
  status?: string | null
  expired: boolean
  previewUrl?: string | null
  message?: string | null
  messageId?: number | null
}

interface AgentOption {
  key: string
  name: string
  shortName: string
  description: string
}

interface PendingImage {
  localId: string
  file: File
  previewUrl: string
  attachmentId?: number
  progress: number
  status: 'uploading' | 'ready' | 'failed'
  error?: string
}

const router = useRouter()
const message = useMessage()
const authStore = useAuthStore()
const prompt = ref('')
const isMultiline = ref(false)
const streaming = ref(false)
const workPanelOpen = ref(false)
const agentMenuOpen = ref(false)
const thinkingConfigOpen = ref(false)
const deleteConfirmOpen = ref(false)
const deletingConversation = ref(false)
const sidebarCollapsed = ref(false)
const activeConversationId = ref<number | null>(null)
const activeAgentName = ref('通用 Agent')
const activeAgentKey = ref<string | null>(null)
const thinkingMode = ref<ThinkingMode>('instant')
const openConversationMenuId = ref<number | null>(null)
const conversationMenuPosition = ref({ top: 0, left: 0 })
const pendingDeleteConversationId = ref<number | null>(null)
const streamControllerRef = ref<AbortController | null>(null)
const imageInputRef = ref<HTMLInputElement | null>(null)
const pendingImages = ref<PendingImage[]>([])
const imagePreviewUrl = ref<string | null>(null)
const historyImagePickerOpen = ref(false)
const selectedReuseAttachmentIds = ref<number[]>([])
const selectedModel = ref<number | null>(null)
const availableModels = ref<AvailableModelRecord[]>([])
const reasoningNow = ref(Date.now())
let reasoningTimer: ReturnType<typeof window.setInterval> | null = null
let sentLocalPreviewUrls = new Set<string>()

watch(prompt, (val) => {
  isMultiline.value = val.includes('\n') || val.length > 40
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

const selectedModelItem = computed(() =>
  availableModels.value.find((item) => item.modelId === selectedModel.value) ?? null
)
const uploadMenuOptions = computed(() => [
  {
    key: 'upload-image',
    label: selectedModelItem.value?.imageInputSupported
      ? '上传照片和文件'
      : '上传照片和文件（当前模型不支持图片输入）',
    disabled: !selectedModelItem.value?.imageInputSupported
  },
  {
    key: 'reuse-image',
    label: historyImageReuseDisabledReason.value
      ? `选择历史图片（${historyImageReuseDisabledReason.value}）`
      : '选择历史图片',
    disabled: Boolean(historyImageReuseDisabledReason.value)
  }
])
const hasUploadingImage = computed(() => pendingImages.value.some((image) => image.status === 'uploading'))
const hasFailedImage = computed(() => pendingImages.value.some((image) => image.status === 'failed'))
const firstReasoningModel = computed(() => availableModels.value.find((item) => item.reasoningSupported) ?? null)
const activeMenuConversation = computed(() =>
  conversations.value.find((item) => item.id === openConversationMenuId.value) ?? null
)
const activeConversation = computed(() =>
  conversations.value.find((item) => item.id === activeConversationId.value) ?? null
)

const agentOptions = ref<AgentOption[]>([])

const conversations = ref<ConversationItem[]>([])

const messageMap = ref<Record<number, ChatMessageItem[]>>({})

const todos = [
  { id: 'todo-1', title: '理解用户目标', done: true },
  { id: 'todo-2', title: '生成执行计划', done: false },
  { id: 'todo-3', title: '输出结果与预览', done: false }
]

const currentMessages = computed(() =>
  activeConversationId.value ? messageMap.value[activeConversationId.value] ?? [] : []
)
const activeAgentLocked = computed(() =>
  !canClearAgentSelection({
    activeConversation: activeConversation.value,
    messageCount: currentMessages.value.length
  })
)
const hasMessages = computed(() => currentMessages.value.length > 0)
const userInitial = computed(() => (authStore.displayName || '用户').trim().slice(0, 1).toUpperCase())
const reusableHistoryAttachments = computed(() => {
  const seenAttachmentIds = new Set<number>()
  const attachments = selectReusableImageAttachments(
    currentMessages.value.flatMap((item) => item.attachments ?? [])
  )

  return attachments.filter((item) => {
    if (seenAttachmentIds.has(item.attachmentId)) {
      return false
    }
    seenAttachmentIds.add(item.attachmentId)
    return true
  })
})
const selectedReuseAttachments = computed(() => {
  const selectedIds = new Set(selectedReuseAttachmentIds.value)
  return reusableHistoryAttachments.value.filter((item) => selectedIds.has(item.attachmentId))
})
const composerImageCount = computed(() => pendingImages.value.length + selectedReuseAttachments.value.length)
const canSubmitContent = computed(() => Boolean(prompt.value.trim()) || composerImageCount.value > 0)
const sendDisabled = computed(() =>
  streaming.value
  || hasUploadingImage.value
  || hasFailedImage.value
  || !canSubmitContent.value
  || (composerImageCount.value > 0 && !selectedModelItem.value?.imageInputSupported)
)
const historyImageReuseDisabledReason = computed(() => {
  if (!selectedModelItem.value?.imageInputSupported) {
    return '当前模型不支持图片输入'
  }

  if (!reusableHistoryAttachments.value.length) {
    return '当前会话暂无可复用图片'
  }

  return ''
})

function mapConversation(item: ConversationRecord): ConversationItem {
  return mapConversationRecord(item)
}

function mapMessage(item: ChatMessageRecord): ChatMessageItem {
  return {
    id: item.messageId,
    role: item.role.toLowerCase() === 'user' ? 'user' : 'assistant',
    content: item.content,
    reasoningContent: item.reasoningContent ?? '',
    reasoningExpanded: false,
    status: item.status,
    attachments: item.attachments?.map(mapAttachment) ?? []
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

async function loadConversations() {
  try {
    const data = await fetchConversations()
    conversations.value = data.map(mapConversation)
    if (activeConversationId.value) {
      syncActiveAgentFromConversation()
    }

    if (!activeConversationId.value && conversations.value.length) {
      activeConversationId.value = conversations.value[0].id
      syncActiveAgentFromConversation(conversations.value[0])
      await loadMessages(conversations.value[0].id)
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载会话失败')
  }
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

function createConversation() {
  // 如果当前已处于空聊天状态，不重复创建
  if (activeConversationId.value === null) {
    return
  }
  // 重置到空状态，让用户发送消息时才创建新对话
  activeConversationId.value = null
  workPanelOpen.value = false
  prompt.value = ''
  clearPendingImages()
  clearSelectedReuseAttachments()
}

async function switchConversation(conversationId: number) {
  activeConversationId.value = conversationId
  openConversationMenuId.value = null
  prompt.value = ''
  clearPendingImages()
  clearSelectedReuseAttachments()
  syncActiveAgentFromConversation()
  await loadMessages(conversationId)
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

function toggleConversationMenu(conversationId: number, event: MouseEvent) {
  if (openConversationMenuId.value === conversationId) {
    openConversationMenuId.value = null
    return
  }

  const trigger = event.currentTarget as HTMLElement
  const rect = trigger.getBoundingClientRect()
  const menuWidth = 118
  const gap = 6
  const viewportPadding = 8
  const rightSideLeft = rect.right + gap
  const left = rightSideLeft + menuWidth > window.innerWidth - viewportPadding
    ? Math.max(viewportPadding, rect.left - menuWidth - gap)
    : rightSideLeft

  conversationMenuPosition.value = {
    top: Math.min(rect.top, window.innerHeight - 88),
    left
  }
  openConversationMenuId.value = conversationId
}

async function startRenameConversation(conversation: ConversationItem) {
  openConversationMenuId.value = null
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
  openConversationMenuId.value = null
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
    if (activeConversationId.value === conversationId) {
      clearSelectedReuseAttachments()
      clearPendingImages()
      activeConversationId.value = conversations.value[0]?.id ?? null
      if (activeConversationId.value) {
        syncActiveAgentFromConversation()
        await loadMessages(activeConversationId.value)
      } else {
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
  agentMenuOpen.value = false
  createConversation()
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
}

function handleUploadMenuSelect(key: string | number) {
  if (key === 'reuse-image') {
    openHistoryImagePicker()
    return
  }

  if (key !== 'upload-image') {
    return
  }

  if (!selectedModelItem.value?.imageInputSupported) {
    message.warning('当前模型不支持图片输入')
    return
  }

  imageInputRef.value?.click()
}

function handleImageSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])

  if (!files.length) {
    return
  }

  if (!selectedModelItem.value?.imageInputSupported) {
    message.warning('当前模型不支持图片输入')
    input.value = ''
    return
  }

  const countError = validateImageCount(composerImageCount.value, files.length)
  if (countError) {
    message.warning(countError)
    input.value = ''
    return
  }

  for (const file of files) {
    const fileError = validateImageFile(file)
    if (fileError) {
      message.warning(fileError)
      continue
    }

    const item: PendingImage = {
      localId: createLocalImageId(),
      file,
      previewUrl: URL.createObjectURL(file),
      progress: 0,
      status: 'uploading'
    }
    pendingImages.value.push(item)
    void uploadImage(item.localId)
  }

  input.value = ''
}

function openHistoryImagePicker() {
  if (historyImageReuseDisabledReason.value) {
    message.warning(historyImageReuseDisabledReason.value)
    return
  }

  historyImagePickerOpen.value = true
}

function isReuseAttachmentSelected(attachmentId: number) {
  return selectedReuseAttachmentIds.value.includes(attachmentId)
}

function toggleReuseAttachment(attachmentId: number) {
  if (isReuseAttachmentSelected(attachmentId)) {
    removeSelectedReuseAttachment(attachmentId)
    return
  }

  const attachment = reusableHistoryAttachments.value.find((item) => item.attachmentId === attachmentId)
  if (!attachment) {
    message.warning('只能选择未过期图片')
    return
  }

  const countError = validateReuseImageCount(pendingImages.value.length, selectedReuseAttachmentIds.value.length + 1)
  if (countError) {
    message.warning(countError)
    return
  }

  selectedReuseAttachmentIds.value = [...selectedReuseAttachmentIds.value, attachmentId]
}

function createLocalImageId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

async function uploadImage(localId: string) {
  const item = pendingImages.value.find((image) => image.localId === localId)
  if (!item) {
    return
  }

  try {
    const signed = await createImageUploadUrl(item.file.name, item.file.type, item.file.size)
    if (!updatePendingImageByLocalId(pendingImages, localId, { attachmentId: signed.attachmentId })) {
      return
    }
    await uploadImageFile(signed.uploadUrl, signed.headers, item.file, (progress) => {
      updatePendingImageByLocalId(pendingImages, localId, { progress })
    })
    if (!pendingImages.value.some((image) => image.localId === localId)) {
      return
    }
    await confirmImageUpload(signed.attachmentId)
    updatePendingImageByLocalId(pendingImages, localId, {
      status: 'ready',
      progress: 100
    })
  } catch {
    updatePendingImageByLocalId(pendingImages, localId, {
      status: 'failed',
      error: '图片上传失败，请重试'
    })
  }
}

function uploadImageFile(uploadUrl: string, headers: Record<string, string>, file: File, onProgress: (progress: number) => void) {
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', uploadUrl)

    for (const [key, value] of Object.entries(headers)) {
      xhr.setRequestHeader(key, value)
    }

    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable || event.total <= 0) {
        return
      }
      onProgress(Math.min(99, Math.max(1, Math.round((event.loaded / event.total) * 100))))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }
      reject(new Error(`图片上传失败：${xhr.status}`))
    }
    xhr.onerror = () => reject(new Error('图片上传失败'))
    xhr.send(file)
  })
}

function removePendingImage(localId: string) {
  const image = pendingImages.value.find((item) => item.localId === localId)
  if (!image) {
    return
  }

  revokePendingImageUrl(image)
  pendingImages.value = pendingImages.value.filter((item) => item.localId !== localId)
}

function removeSelectedReuseAttachment(attachmentId: number) {
  selectedReuseAttachmentIds.value = selectedReuseAttachmentIds.value.filter((item) => item !== attachmentId)
}

function clearSelectedReuseAttachments() {
  selectedReuseAttachmentIds.value = []
  historyImagePickerOpen.value = false
}

function clearPendingImages() {
  for (const image of pendingImages.value) {
    revokePendingImageUrl(image)
  }
  pendingImages.value = []
  imagePreviewUrl.value = null
}

function revokeSentPreviewUrls() {
  for (const previewUrl of sentLocalPreviewUrls) {
    if (imagePreviewUrl.value === previewUrl) {
      imagePreviewUrl.value = null
    }
    URL.revokeObjectURL(previewUrl)
  }
  sentLocalPreviewUrls = new Set<string>()
}

async function refreshActiveConversationMessages() {
  const conversationId = activeConversationId.value
  if (conversationId && conversationId > 0) {
    await loadMessages(conversationId)
  }
}

function revokePendingImageUrl(image: PendingImage) {
  if (imagePreviewUrl.value === image.previewUrl) {
    imagePreviewUrl.value = null
  }
  URL.revokeObjectURL(image.previewUrl)
}

function handleImagePreviewVisibleChange(show: boolean) {
  if (!show) {
    imagePreviewUrl.value = null
  }
}

function canPreviewAttachment(attachment: ChatAttachmentItem) {
  return !attachment.expired && Boolean(attachment.previewUrl)
}

function previewMessageAttachment(attachment: ChatAttachmentItem) {
  if (!canPreviewAttachment(attachment)) {
    return
  }

  imagePreviewUrl.value = attachment.previewUrl ?? null
}

function copyMessage(content: string) {
  navigator.clipboard.writeText(content).then(
    () => message.success('已复制到剪贴板'),
    () => message.error('复制失败')
  )
}

function toggleReasoning(messageItem: ChatMessageItem) {
  messageItem.reasoningExpanded = !messageItem.reasoningExpanded
}

function reasoningStatusText(messageItem: ChatMessageItem) {
  const elapsedMs = reasoningElapsedMs(messageItem)

  if (elapsedMs === null) {
    return '思考过程'
  }

  return buildReasoningStatusText(elapsedMs, Boolean(messageItem.reasoningFinishedAt))
}

function reasoningElapsedMs(messageItem: ChatMessageItem) {
  if (!messageItem.reasoningStartedAt) {
    return null
  }

  return Math.max(0, (messageItem.reasoningFinishedAt ?? reasoningNow.value) - messageItem.reasoningStartedAt)
}

function startReasoningTiming(messageItem: ChatMessageItem) {
  if (!messageItem.reasoningStartedAt) {
    const now = Date.now()
    messageItem.reasoningStartedAt = now
    reasoningNow.value = now
    syncReasoningTimer()
  }
}

function finishReasoningTiming(messageItem?: ChatMessageItem) {
  if (!messageItem?.reasoningStartedAt || messageItem.reasoningFinishedAt) {
    return
  }

  const now = Date.now()
  messageItem.reasoningFinishedAt = now
  reasoningNow.value = now
  syncReasoningTimer()
}

function syncReasoningTimer() {
  const hasActiveReasoning = currentMessages.value.some(
    (item) => item.reasoningStartedAt && !item.reasoningFinishedAt
  )

  if (hasActiveReasoning && !reasoningTimer) {
    reasoningTimer = window.setInterval(() => {
      reasoningNow.value = Date.now()
    }, 1000)
    return
  }

  if (!hasActiveReasoning && reasoningTimer) {
    window.clearInterval(reasoningTimer)
    reasoningTimer = null
  }
}

function closeStream() {
  streamControllerRef.value?.abort()
  streamControllerRef.value = null
  streaming.value = false
  syncReasoningTimer()
}

function handleSend() {
  const trimmedPrompt = prompt.value.trim()

  if (streaming.value) {
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

  if (!trimmedPrompt && composerImageCount.value === 0) {
    return
  }

  if (thinkingMode.value === 'thinking' && !ensureThinkingModelSelected()) {
    return
  }

  closeStream()

  if (!activeConversationId.value) {
    const nextId = -Date.now()
    conversations.value.unshift({
      id: nextId,
      title: '新聊天',
      updatedAt: '刚刚',
      agentKey: activeAgentKey.value
    })
    messageMap.value[nextId] = []
    activeConversationId.value = nextId
  }

  const conversationId = activeConversationId.value
  const messages = ensureConversationMessages(conversationId)
  const assistantMessageId = -Date.now()
  let currentAssistantMessageId = assistantMessageId
  const currentReadyPendingImages = pendingImages.value.filter(
    (image) => image.status === 'ready' && typeof image.attachmentId === 'number'
  )
  const currentPendingAttachments: ChatAttachmentItem[] = currentReadyPendingImages.map((image) => ({
    attachmentId: image.attachmentId as number,
    filename: image.file.name,
    contentType: image.file.type,
    status: 'READY',
    expired: false,
    previewUrl: image.previewUrl,
    message: null,
    messageId: null
  }))
  const currentReuseAttachments = selectedReuseAttachments.value.map((attachment) => ({ ...attachment }))
  const currentMessageAttachments = [...currentPendingAttachments, ...currentReuseAttachments]

  messages.push({
    id: assistantMessageId - 1,
    role: 'user',
    content: trimmedPrompt,
    attachments: currentMessageAttachments
  })

  messages.push({
    id: assistantMessageId,
    role: 'assistant',
    content: '',
    reasoningContent: '',
    reasoningExpanded: thinkingMode.value === 'thinking',
    reasoningStartedAt: undefined,
    reasoningFinishedAt: undefined,
    status: 'STREAMING'
  })

  const currentPrompt = trimmedPrompt
  const currentThinkingEnabled = thinkingMode.value === 'thinking'
  const currentAttachmentIds = currentReadyPendingImages
    .map((image) => image.attachmentId as number)
  const currentReuseAttachmentIds = currentReuseAttachments.map((attachment) => attachment.attachmentId)
  const currentSentPreviewUrls = currentReadyPendingImages.map((image) => image.previewUrl)
  currentSentPreviewUrls.forEach((previewUrl) => sentLocalPreviewUrls.add(previewUrl))
  prompt.value = ''
  pendingImages.value = []
  clearSelectedReuseAttachments()
  streaming.value = true

  streamControllerRef.value = openChatStream(
    {
      prompt: currentPrompt,
      token: authStore.token,
      conversationId: conversationId > 0 ? conversationId : null,
      modelId: selectedModel.value,
      agentKey: activeAgentKey.value,
      thinkingEnabled: currentThinkingEnabled,
      attachmentIds: currentAttachmentIds,
      reuseAttachmentIds: currentReuseAttachmentIds
    },
    {
      onConversation: (data) => {
        const previousId = activeConversationId.value
        if (previousId && previousId < 0) {
          messageMap.value[data.conversationId] = messageMap.value[previousId] ?? []
          delete messageMap.value[previousId]
          activeConversationId.value = data.conversationId
        }

        conversations.value = [
          { id: data.conversationId, title: data.title, updatedAt: '刚刚', agentKey: activeAgentKey.value },
          ...conversations.value.filter((item) => item.id !== data.conversationId && item.id !== previousId)
        ]
      },
      onMessage: (data) => {
        const assistantMessage = findAssistantMessage(currentAssistantMessageId)
        if (assistantMessage) {
          assistantMessage.id = data.messageId
          currentAssistantMessageId = data.messageId
        }
      },
      onReasoningDelta: (data) => {
        if (!currentThinkingEnabled) {
          return
        }
        const assistantMessage = findAssistantMessage(currentAssistantMessageId)
        if (assistantMessage) {
          startReasoningTiming(assistantMessage)
          assistantMessage.reasoningContent = `${assistantMessage.reasoningContent ?? ''}${data.content}`
        }
      },
      onAnswerDelta: (data) => {
        const assistantMessage = findAssistantMessage(currentAssistantMessageId)
        if (assistantMessage) {
          assistantMessage.content += data.content
        }
      },
      onMessageCompleted: (data) => {
        const assistantMessage = findAssistantMessage(data.messageId ?? currentAssistantMessageId)
        if (assistantMessage) {
          finishReasoningTiming(assistantMessage)
          assistantMessage.status = data.status ?? 'COMPLETED'
        }
      },
      onError: (data) => {
        finishReasoningTiming(findAssistantMessage(currentAssistantMessageId))
        message.error(data.message ?? '\u6d41\u5f0f\u54cd\u5e94\u5931\u8d25')
        closeStream()
        void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
      },
      onDone: () => {
        finishReasoningTiming(findAssistantMessage(currentAssistantMessageId))
        closeStream()
        void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
        void loadConversations()
      },
      onTransportError: () => {
        finishReasoningTiming(findAssistantMessage(currentAssistantMessageId))
        closeStream()
        void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)
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

function handleLogout() {
  closeStream()
  authStore.logout()
  void router.push('/login')
}

onBeforeUnmount(() => {
  closeStream()
  clearPendingImages()
  revokeSentPreviewUrls()
  if (reasoningTimer) {
    window.clearInterval(reasoningTimer)
    reasoningTimer = null
  }
})

onMounted(() => {
  void loadAgents()
  void loadModels()
  void loadConversations()
})
</script>

<style scoped>
.chat-shell {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  height: 100vh;
  background: #ffffff;
  color: #111827;
  transition: grid-template-columns 0.25s ease;
}

.chat-shell.panel-open {
  grid-template-columns: 240px minmax(0, 1fr) 340px;
}

.chat-shell.sidebar-collapsed {
  grid-template-columns: 0px minmax(0, 1fr);
}

.chat-shell.sidebar-collapsed.panel-open {
  grid-template-columns: 0px minmax(0, 1fr) 340px;
}

.chat-shell.sidebar-collapsed .sidebar {
  padding: 0;
  border-right: 0;
  visibility: hidden;
  pointer-events: none;
}

.sidebar {
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding: 12px 8px;
  background: #f7f8fa;
  border-right: 1px solid #e8eaed;
  min-width: 0;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 8px 16px;
}

.sidebar-header strong {
  font-size: 20px;
  letter-spacing: -0.03em;
  color: #1a1a2e;
}

.icon-button,
.nav-item,
.recent-title,
.recent-more,
.conversation-action-menu button,
.round-action,
.send-orb,
.message-tools button,
.image-preview-button,
.image-remove-button,
.agent-option,
.mode-option,
.message-attachment,
.history-image-option {
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.icon-button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 8px;
  color: #6b7280;
}

.icon-button:hover {
  background: #e8eaed;
}

svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.primary-nav {
  display: grid;
  gap: 4px;
  position: relative;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 38px;
  width: 100%;
  padding: 8px 10px;
  border-radius: 10px;
  color: #444;
  text-align: left;
  font-weight: 500;
}

.nav-item.active,
.nav-item:hover,
.recent-item.active,
.recent-item:hover {
  background: #e8eaed;
  color: #1a1a2e;
}

.nav-icon {
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  color: #555;
}

.chevron {
  display: grid;
  margin-left: auto;
  color: #6b7280;
}

.chevron svg {
  width: 15px;
  height: 15px;
}

.agent-menu-wrap {
  position: relative;
}

.agent-menu {
  position: absolute;
  z-index: 20;
  top: 0;
  left: calc(100% + 8px);
  display: grid;
  width: 286px;
  gap: 4px;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.14);
  opacity: 0;
  pointer-events: none;
  transform: translateX(-6px);
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.agent-menu-wrap:hover .agent-menu,
.agent-menu-wrap:focus-within .agent-menu,
.agent-menu-wrap.open .agent-menu {
  opacity: 1;
  pointer-events: auto;
  transform: translateX(0);
}

.agent-option {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 10px;
  border-radius: 12px;
  text-align: left;
}

.agent-option:hover {
  background: #f3f4f6;
}

.agent-avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  background: #eff6ff;
  color: #2563eb;
  font-weight: 700;
}

.agent-option strong,
.agent-option small {
  display: block;
}

.agent-option strong {
  color: #1a1a2e;
  font-size: 14px;
}

.agent-option small {
  margin-top: 3px;
  color: #6b7280;
  font-size: 12px;
}

.recent-section {
  flex: 1;
  overflow-y: auto;
  overflow-x: visible;
  margin-top: 24px;
  padding: 0 4px;
}

.recent-section h2 {
  margin: 0 4px 8px;
  color: #999;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.recent-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  overflow: visible;
  padding: 2px 4px 2px 8px;
  border-radius: 10px;
  color: #555;
  font-size: 14px;
}

.recent-title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  padding: 7px 0;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-actions {
  position: relative;
  opacity: 0;
  pointer-events: none;
}

.recent-item:hover .recent-actions,
.recent-item.active .recent-actions,
.recent-actions:focus-within {
  opacity: 1;
  pointer-events: auto;
}

.recent-more {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 8px;
  color: #6b7280;
}

.recent-more:hover {
  background: #dfe3e8;
}

.conversation-action-menu {
  position: fixed;
  z-index: 1000;
  display: grid;
  width: 118px;
  gap: 2px;
  padding: 6px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 14px 36px rgba(15, 23, 42, 0.14);
}

.conversation-action-menu button {
  padding: 7px 8px;
  border-radius: 6px;
  text-align: left;
  font-size: 13px;
}

.conversation-action-menu button:hover {
  background: #f3f4f6;
}

.conversation-action-menu .danger {
  color: #dc2626;
}

.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 8px 0;
  border-top: 1px solid #ececf1;
}

.user-avatar {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 999px;
  background: #2563eb;
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
}

.user-meta {
  min-width: 0;
  flex: 1;
}

.user-meta strong {
  display: block;
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.subtle-spinner {
  width: 18px;
  height: 18px;
  border: 2px dashed #999;
  border-radius: 999px;
  opacity: 0.8;
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

.landing-composer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  width: min(100%, var(--chat-column-width));
  padding: 10px 16px;
  border: 1px solid #d9d9e3;
  border-radius: 24px;
  background: #ffffff;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.08);
  position: relative;
}

.landing-composer.is-multiline {
  gap: 4px;
}

.landing-composer.compact {
  width: min(100%, var(--chat-column-width));
}

/* Single-line order: +, agent, input, model, send */
.composer-upload { order: 1; }
.composer-agent { order: 2; }
.composer-input { order: 3; }
.composer-model { order: 4; }
.composer-send { order: 5; }

/* Multiline: input on first row, rest on second row */
.landing-composer.is-multiline .composer-input {
  flex: 1 1 100%;
  order: 1;
}

.landing-composer.is-multiline .composer-upload { order: 2; }
.landing-composer.is-multiline .composer-agent { order: 3; }
.landing-composer.is-multiline .composer-model { order: 4; margin-left: auto; }
.landing-composer.is-multiline .composer-send { order: 5; }

.landing-composer :deep(.n-input),
.landing-composer :deep(.n-input-wrapper),
.landing-composer :deep(textarea) {
  background: transparent;
}

.landing-composer :deep(.n-input) {
  flex: 1;
  min-width: 0;
}

.composer-input {
  flex: 1;
  min-width: 0;
}

.composer-upload,
.composer-agent,
.composer-model,
.composer-send {
  flex-shrink: 0;
}

.landing-composer :deep(.n-input__border),
.landing-composer :deep(.n-input__state-border) {
  display: none;
}

.round-action {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 999px;
  color: #6b7280;
}

.round-action:hover,
.message-tools button:hover {
  background: #f0f0f0;
}

.send-orb {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 999px;
  background: #2563eb;
  color: #ffffff;
}

.send-orb:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.send-orb svg {
  width: 18px;
  height: 18px;
}

.pending-image-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  width: min(100%, var(--chat-column-width));
  margin-bottom: 10px;
}

.pending-image-item {
  position: relative;
  width: 56px;
  height: 56px;
}

.pending-image-item.reused .image-preview-button {
  outline: 2px solid #2563eb;
  outline-offset: 2px;
}

.pending-image-list.single .pending-image-item {
  width: 148px;
  height: 148px;
}

.image-preview-button {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  padding: 0;
  border-radius: 8px;
  background: #f3f4f6;
  cursor: zoom-in;
  overflow: hidden;
}

.image-preview-button img {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 8px;
  object-fit: cover;
}

.image-remove-button {
  position: absolute;
  top: -7px;
  right: -7px;
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  padding: 0;
  border: 1px solid #ffffff;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  cursor: pointer;
  font-size: 16px;
  font-family: Arial, Helvetica, sans-serif;
  line-height: 20px;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.2);
}

.image-progress,
.image-error {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: rgba(17, 24, 39, 0.58);
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
}

.image-error {
  background: rgba(185, 28, 28, 0.72);
}

.image-reuse-badge {
  position: absolute;
  left: 5px;
  bottom: 5px;
  padding: 2px 5px;
  border-radius: 4px;
  background: rgba(37, 99, 235, 0.92);
  color: #ffffff;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.2;
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

.history-image-modal {
  width: min(92vw, 560px);
  padding: 18px;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.22);
}

.history-image-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.history-image-header h3 {
  margin: 0;
  color: #111827;
  font-size: 18px;
  line-height: 1.35;
}

.history-image-header span {
  display: block;
  margin-top: 4px;
  color: #6b7280;
  font-size: 13px;
}

.history-image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(88px, 1fr));
  gap: 10px;
  max-height: min(56vh, 420px);
  overflow-y: auto;
}

.history-image-option {
  position: relative;
  display: block;
  aspect-ratio: 1;
  padding: 0;
  border-radius: 8px;
  background: #f3f4f6;
  overflow: hidden;
}

.history-image-option img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.history-image-option.selected {
  outline: 3px solid #2563eb;
  outline-offset: -3px;
}

.history-image-option span {
  position: absolute;
  left: 6px;
  bottom: 6px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(37, 99, 235, 0.92);
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
}

.agent-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 16px;
  background: #e0e7ff;
  color: #4f46e5;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}

.agent-tag-close {
  display: grid;
  width: 16px;
  height: 16px;
  place-items: center;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #4f46e5;
  font-size: 14px;
  cursor: pointer;
  line-height: 1;
}

.agent-tag-close:hover {
  background: #c7d2fe;
}

.conversation-view {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  height: 100vh;
}

.message-list {
  overflow-y: auto;
  padding: 70px 24px 24px;
}

.message-row {
  display: grid;
  width: min(100%, var(--chat-column-width));
  margin: 0 auto 28px;
}

.message-row.user {
  justify-items: end;
}

.user-bubble {
  max-width: min(70%, 560px);
  padding: 12px 16px;
  border-radius: 22px;
  background: #f1f1f1;
  color: #111827;
  line-height: 1.7;
  white-space: pre-wrap;
}

.user-message-text {
  margin: 0;
}

.message-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.message-attachment {
  display: grid;
  width: 72px;
  height: 72px;
  place-items: center;
  padding: 0;
  border-radius: 8px;
  background: #e5e7eb;
  color: #6b7280;
  overflow: hidden;
}

.message-attachment img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.message-attachment span {
  padding: 6px;
  font-size: 12px;
  line-height: 1.35;
  text-align: center;
  white-space: normal;
}

.message-attachment.disabled {
  cursor: not-allowed;
  opacity: 0.78;
}

.assistant-message {
  color: #111827;
  font-size: 16px;
  line-height: 1.75;
}

.assistant-message p {
  margin: 0;
  white-space: pre-wrap;
}

.assistant-markdown {
  display: grid;
  gap: 10px;
}

.assistant-markdown :deep(p),
.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol),
.assistant-markdown :deep(pre),
.assistant-markdown :deep(blockquote),
.assistant-markdown :deep(table) {
  margin: 0;
}

.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol) {
  display: grid;
  gap: 4px;
  padding-left: 22px;
}

.assistant-markdown :deep(code) {
  border-radius: 5px;
  background: #f1f5f9;
  padding: 2px 5px;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 0.9em;
}

.assistant-markdown :deep(pre) {
  overflow: auto;
  padding: 12px;
  border-radius: 8px;
  background: #0f172a;
  color: #e5e7eb;
}

.assistant-markdown :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.assistant-markdown :deep(blockquote) {
  padding-left: 12px;
  border-left: 3px solid #cbd5e1;
  color: #64748b;
}

.assistant-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.assistant-markdown :deep(th),
.assistant-markdown :deep(td) {
  padding: 7px 8px;
  border: 1px solid #e5e7eb;
  text-align: left;
}

.reasoning-block {
  display: grid;
  gap: 14px;
  margin-bottom: 18px;
  padding: 0 0 0 2px;
  border-bottom: 1px solid #edf0f3;
}

.reasoning-block.collapsed {
  gap: 0;
}

.reasoning-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: max-content;
  max-width: 100%;
  padding: 0 0 12px;
  border: 0;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  line-height: 1.4;
  text-align: left;
}

.reasoning-toggle span {
  font-size: 13px;
  font-weight: 500;
}

.reasoning-caret {
  width: 7px;
  height: 7px;
  border-right: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
  transform: translateY(-1px) rotate(45deg);
  transition: transform 0.16s ease;
}

.reasoning-toggle[aria-expanded='false'] .reasoning-caret {
  transform: translateY(1px) rotate(-45deg);
}

.reasoning-content {
  margin: 0 0 16px 0;
  padding-left: 14px;
  border-left: 2px solid #e5e7eb;
  color: #4b5563;
  font-size: 14px;
  line-height: 1.85;
  white-space: pre-wrap;
}

.message-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 16px;
}

.message-tools button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border-radius: 8px;
  color: #6b7280;
  font-size: 13px;
}

.message-tools button svg {
  width: 14px;
  height: 14px;
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

.work-panel {
  display: grid;
  align-content: start;
  gap: 12px;
  min-height: 100vh;
  padding: 14px;
  border-left: 1px solid #ececf1;
  background: #fbfbfb;
}

.thinking-config-modal {
  width: min(320px, calc(100vw - 32px));
  max-width: calc(100vw - 32px);
}

.delete-conversation-modal {
  display: grid;
  width: min(320px, calc(100vw - 32px));
  padding: 0;
  border-radius: 8px;
  background: #ffffff;
  margin: auto;
  overflow: hidden;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.16);
}

.thinking-config-modal :deep(.n-card-header) {
  padding: 18px 18px 8px;
}

.delete-modal-header {
  padding: 18px 20px 8px;
}

.delete-conversation-modal h3 {
  margin: 0;
  color: #111827;
  font-size: 16px;
  font-weight: 600;
}

.thinking-config-modal :deep(.n-card__content) {
  display: grid;
  gap: 10px;
  padding: 12px 18px 18px;
}

.delete-modal-body {
  padding: 8px 20px 18px;
}

.delete-conversation-modal p {
  margin: 0;
  color: #4b5563;
  font-size: 14px;
  line-height: 1.7;
}

.delete-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 20px;
  border-top: 1px solid #eef0f3;
  background: #fafafa;
}

.config-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 38px;
  color: #111827;
  font-size: 14px;
}

.config-select {
  width: 150px;
}

.config-divider {
  height: 1px;
  background: #e5e7eb;
}

.mode-option {
  position: relative;
  display: grid;
  gap: 3px;
  padding: 8px 34px 8px 0;
  text-align: left;
}

.mode-option strong {
  color: #111827;
  font-size: 15px;
  font-weight: 500;
}

.mode-option span,
.thinking-config-modal p {
  margin: 0;
  color: #8a8f98;
  font-size: 13px;
}

.mode-option.active::after {
  position: absolute;
  top: 18px;
  right: 4px;
  content: "";
  width: 7px;
  height: 12px;
  border: solid #111827;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}

.work-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 2px;
}

.work-panel-header strong,
.work-panel-header span,
.panel-title span,
.panel-title small {
  display: block;
}

.work-panel-header strong,
.panel-title span {
  color: #111827;
  font-size: 14px;
}

.work-panel-header span,
.panel-title small,
.preview-placeholder p {
  color: #6b7280;
  font-size: 12px;
}

.panel-card {
  display: grid;
  gap: 14px;
  padding: 14px;
  border: 1px solid #ececf1;
  border-radius: 14px;
  background: #ffffff;
}

.panel-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.todo-item {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #374151;
  font-size: 14px;
}

.todo-item input {
  accent-color: #2563eb;
}

.preview-card {
  min-height: 260px;
}

.preview-placeholder {
  display: grid;
  align-content: center;
  gap: 16px;
  min-height: 190px;
  padding: 16px;
  border: 1px dashed #d9d9e3;
  border-radius: 12px;
  background: #f7f7f8;
  text-align: center;
}

.file-stack {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.file-stack span {
  padding: 8px 10px;
  border-radius: 10px;
  background: #ffffff;
  color: #374151;
  font-size: 12px;
  font-weight: 700;
  box-shadow: inset 0 0 0 1px #ececf1;
}

@media (max-width: 1020px) {
  .chat-shell.panel-open,
  .chat-shell {
    grid-template-columns: 240px minmax(0, 1fr);
  }

  .chat-shell.sidebar-collapsed {
    grid-template-columns: 0px minmax(0, 1fr);
  }

  .chat-shell.sidebar-collapsed.panel-open {
    grid-template-columns: 0px minmax(0, 1fr);
  }

  .work-panel {
    grid-column: 1 / -1;
    min-height: auto;
    border-top: 1px solid #ececf1;
    border-left: 0;
  }
}

@media (max-width: 640px) {
  .chat-shell,
  .chat-shell.panel-open {
    grid-template-columns: 1fr;
  }

  .chat-shell.sidebar-collapsed {
    grid-template-columns: 1fr;
  }

  .sidebar {
    min-height: auto;
    border-right: 0;
    border-bottom: 1px solid #ececf1;
  }

  .recent-section {
    max-height: 180px;
  }

  .landing-composer {
    padding: 8px 12px;
  }

  .agent-menu {
    top: calc(100% + 8px);
    left: 0;
    width: min(86vw, 286px);
  }
}
</style>
