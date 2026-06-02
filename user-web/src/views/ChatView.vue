<template>
  <div class="chat-shell" :class="{ 'panel-open': workPanelOpen, 'sidebar-collapsed': sidebarCollapsed }">
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
        <button
          v-for="conversation in conversations"
          :key="conversation.id"
          type="button"
          class="recent-item"
          :class="{ active: conversation.id === activeConversationId }"
          @click="switchConversation(conversation.id)"
        >
          {{ conversation.title }}
        </button>
      </section>

      <div class="sidebar-footer">
        <div class="user-avatar">{{ userInitial }}</div>
        <div class="user-meta">
          <strong>{{ authStore.username || '用户' }}</strong>
        </div>
        <n-button quaternary size="small" @click="handleLogout">退出</n-button>
      </div>
    </aside>

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

        <div class="file-chips" v-if="uploadedFiles.length">
          <span v-for="(file, idx) in uploadedFiles" :key="idx" class="file-chip">
            {{ file.name }}
            <button type="button" class="chip-remove" @click="removeFile(idx)">&times;</button>
          </span>
        </div>

        <div class="landing-composer" :class="{ 'is-multiline': isMultiline }">
          <button type="button" class="round-action composer-upload" aria-label="上传文件" @click="triggerFileUpload">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </button>
          <span v-if="activeAgentName !== '通用 Agent'" class="agent-tag composer-agent">
            {{ activeAgentName }}
            <button type="button" class="agent-tag-close" @click="activeAgentName = '通用 Agent'">&times;</button>
          </span>
          <n-input
            v-model:value="prompt"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 5 }"
            placeholder="有问题，尽管问"
            @keydown.enter.exact.prevent="handleSend"
            class="composer-input"
          />
          <n-popselect v-model:value="selectedModel" :options="modelOptions" trigger="click" placement="top">
            <button type="button" class="model-select composer-model">{{ selectedModel }}</button>
          </n-popselect>
          <button type="button" class="send-orb composer-send" :disabled="streaming" @click="handleSend" aria-label="发送">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </button>
        </div>

        <input
          ref="fileInputRef"
          type="file"
          multiple
          accept="image/*,.doc,.docx"
          style="display: none"
          @change="handleFileSelect"
        />
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
              {{ messageItem.content }}
            </div>

            <div v-else class="assistant-message">
              <p>{{ messageItem.content || '正在生成回复...' }}</p>
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
          <div class="file-chips" v-if="uploadedFiles.length">
            <span v-for="(file, idx) in uploadedFiles" :key="idx" class="file-chip">
              {{ file.name }}
              <button type="button" class="chip-remove" @click="removeFile(idx)">&times;</button>
            </span>
          </div>
          <div class="landing-composer compact" :class="{ 'is-multiline': isMultiline }">
            <button type="button" class="round-action composer-upload" aria-label="上传文件" @click="triggerFileUpload">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
            </button>
            <span v-if="activeAgentName !== '通用 Agent'" class="agent-tag composer-agent">
              {{ activeAgentName }}
              <button type="button" class="agent-tag-close" @click="activeAgentName = '通用 Agent'">&times;</button>
            </span>
            <n-input
              v-model:value="prompt"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 5 }"
              placeholder="有问题，尽管问"
              @keydown.enter.exact.prevent="handleSend"
              class="composer-input"
            />
            <n-popselect v-model:value="selectedModel" :options="modelOptions" trigger="click" placement="top">
              <button type="button" class="model-select composer-model">{{ selectedModel }}</button>
            </n-popselect>
            <button type="button" class="send-orb composer-send" :disabled="streaming" @click="handleSend" aria-label="发送">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M5 12h14M13 6l6 6-6 6" />
              </svg>
            </button>
          </div>
          <p>Agent Hub 也可能会犯错。请核查重要信息。</p>
        </footer>
      </section>
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
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage, NButton, NInput, NPopselect } from 'naive-ui'

import { openChatStream } from '../api/chat'
import { useAuthStore } from '../stores/auth'

type ChatRole = 'user' | 'assistant'

interface ConversationItem {
  id: string
  title: string
  updatedAt: string
}

interface ChatMessageItem {
  id: string
  role: ChatRole
  content: string
}

interface AgentOption {
  key: string
  name: string
  shortName: string
  description: string
}

interface UploadedFile {
  name: string
  size: number
}

const router = useRouter()
const message = useMessage()
const authStore = useAuthStore()
const prompt = ref('')
const isMultiline = ref(false)
const streaming = ref(false)
const workPanelOpen = ref(false)
const agentMenuOpen = ref(false)
const sidebarCollapsed = ref(false)
const activeConversationId = ref<string | null>(null)
const activeAgentName = ref('通用 Agent')
const eventSourceRef = ref<EventSource | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploadedFiles = ref<UploadedFile[]>([])
const selectedModel = ref('Instant')

// 监听输入内容，判断是否多行
watch(prompt, (val) => {
  // 有换行符或长度超过40字符视为多行
  isMultiline.value = val.includes('\n') || val.length > 40
})

const modelOptions = [
  { label: 'Instant', value: 'Instant' },
  { label: 'GPT-4o', value: 'GPT-4o' },
  { label: 'Claude 3.5', value: 'Claude 3.5' },
  { label: 'DeepSeek V3', value: 'DeepSeek V3' },
  { label: 'Qwen Max', value: 'Qwen Max' }
]

const agentOptions: AgentOption[] = [
  { key: 'document-review', name: '文档审核 Agent', shortName: '审', description: '等保、密评、报告材料审查' },
  { key: 'official-writing', name: '公文写作 Agent', shortName: '文', description: '通知、请示、汇报材料起草' },
  { key: 'data-analysis', name: '数据分析 Agent', shortName: '数', description: '表格分析、指标解释、结论提炼' },
  { key: 'project-plan', name: '项目方案 Agent', shortName: '案', description: '技术方案、架构设计、实施计划' }
]

const conversations = ref<ConversationItem[]>([
  { id: 'conv-1', title: '技术方案与架构设计', updatedAt: '今天' }
])

const messageMap = ref<Record<string, ChatMessageItem[]>>({
  'conv-1': [
    {
      id: 'user-demo',
      role: 'user',
      content: '你好'
    },
    {
      id: 'assistant-demo',
      role: 'assistant',
      content:
        '你好！有什么我可以帮你的吗？\n\n看你最近主要在研究：\n\n- Agent 平台架构设计（AgentHub）\n- 多 Agent / Tool Loop / Skill 体系\n- Spring Boot + Vue3 + AI 集成\n- 文档审核系统（等保、密评、报告审核）\n- 智能执法监管平台\n- 大模型落地与工程架构\n\n无论是技术方案、架构设计、PPT 汇报材料、代码实现，还是项目可行性分析，我都可以帮你一起梳理。'
    }
  ]
})

const todos = [
  { id: 'todo-1', title: '理解用户目标', done: true },
  { id: 'todo-2', title: '生成执行计划', done: false },
  { id: 'todo-3', title: '输出结果与预览', done: false }
]

const currentMessages = computed(() =>
  activeConversationId.value ? messageMap.value[activeConversationId.value] ?? [] : []
)
const hasMessages = computed(() => currentMessages.value.length > 0)
const userInitial = computed(() => (authStore.username || '用户').trim().slice(0, 1).toUpperCase())

function ensureConversationMessages(conversationId: string) {
  if (!messageMap.value[conversationId]) {
    messageMap.value[conversationId] = []
  }

  return messageMap.value[conversationId]
}

function createConversation() {
  // 重置到空状态，让用户发送消息时才创建新对话
  activeConversationId.value = null
  workPanelOpen.value = false
  prompt.value = ''
  uploadedFiles.value = []
}

function switchConversation(conversationId: string) {
  activeConversationId.value = conversationId
  prompt.value = ''
  uploadedFiles.value = []
}

function selectAgent(agent: AgentOption) {
  activeAgentName.value = agent.name
  agentMenuOpen.value = false
  createConversation()
}

function triggerFileUpload() {
  fileInputRef.value?.click()
}

function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files) return

  const allowedImageTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/svg+xml', 'image/bmp']
  const allowedDocTypes = [
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
  ]

  for (const file of Array.from(input.files)) {
    if (allowedImageTypes.includes(file.type) || allowedDocTypes.includes(file.type)) {
      uploadedFiles.value.push({ name: file.name, size: file.size })
    } else {
      message.warning(`不支持的文件类型: ${file.name}，请上传图片或 Word 文件`)
    }
  }

  input.value = ''
}

function removeFile(index: number) {
  uploadedFiles.value.splice(index, 1)
}

function copyMessage(content: string) {
  navigator.clipboard.writeText(content).then(
    () => message.success('已复制到剪贴板'),
    () => message.error('复制失败')
  )
}

function closeStream() {
  eventSourceRef.value?.close()
  eventSourceRef.value = null
  streaming.value = false
}

function handleSend() {
  const trimmedPrompt = prompt.value.trim()

  if (!trimmedPrompt || streaming.value) {
    return
  }

  // 如果当前没有活跃对话，先创建新对话
  if (!activeConversationId.value) {
    const nextId = `conv-${Date.now()}`
    conversations.value.unshift({
      id: nextId,
      title: '新聊天',
      updatedAt: '刚刚'
    })
    messageMap.value[nextId] = []
    activeConversationId.value = nextId
  }

  const conversationId = activeConversationId.value
  const messages = ensureConversationMessages(conversationId)
  const userMessageId = `user-${Date.now()}`
  const assistantMessageId = `assistant-${Date.now()}`

  messages.push({
    id: userMessageId,
    role: 'user',
    content: trimmedPrompt
  })

  messages.push({
    id: assistantMessageId,
    role: 'assistant',
    content: ''
  })

  conversations.value = conversations.value.map((item) =>
    item.id === conversationId
      ? { ...item, title: item.title === '新聊天' ? trimmedPrompt.slice(0, 18) : item.title, updatedAt: '刚刚' }
      : item
  )

  const currentPrompt = trimmedPrompt
  prompt.value = ''
  uploadedFiles.value = []
  closeStream()
  streaming.value = true

  const eventSource = openChatStream(currentPrompt, authStore.token)
  eventSourceRef.value = eventSource

  eventSource.onmessage = (event) => {
    const assistantMessage = ensureConversationMessages(conversationId).find(
      (item) => item.id === assistantMessageId
    )

    if (!assistantMessage) {
      return
    }

    assistantMessage.content += event.data
  }

  eventSource.onerror = () => {
    message.warning('流式连接已结束或暂不可用')
    closeStream()
  }
}

function handleLogout() {
  closeStream()
  authStore.logout()
  void router.push('/login')
}

onBeforeUnmount(() => {
  closeStream()
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
.recent-item,
.round-action,
.send-orb,
.model-select,
.message-tools button,
.agent-option {
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
  overflow: auto;
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
  display: block;
  width: 100%;
  overflow: hidden;
  padding: 9px 8px;
  border-radius: 10px;
  color: #555;
  font-size: 14px;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
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
.model-select:hover,
.message-tools button:hover {
  background: #f0f0f0;
}

.model-select {
  padding: 6px 12px;
  border-radius: 999px;
  color: #6b7280;
  font-size: 14px;
  white-space: nowrap;
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

.file-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
  width: min(100%, var(--chat-column-width));
}

.file-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chip-remove {
  display: grid;
  width: 16px;
  height: 16px;
  place-items: center;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #2563eb;
  font-size: 14px;
  cursor: pointer;
  line-height: 1;
}

.chip-remove:hover {
  background: #dbeafe;
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

.assistant-message {
  color: #111827;
  font-size: 16px;
  line-height: 1.75;
}

.assistant-message p {
  margin: 0;
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

  .model-select {
    display: none;
  }

  .agent-menu {
    top: calc(100% + 8px);
    left: 0;
    width: min(86vw, 286px);
  }
}
</style>
