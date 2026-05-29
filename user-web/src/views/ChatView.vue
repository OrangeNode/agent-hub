<template>
  <div class="chat-page">
    <section class="conversation-pane">
      <div class="pane-header">
        <div>
          <h2>会话列表</h2>
          <p>用于承载后续真实会话数据。</p>
        </div>
        <n-button secondary type="primary" @click="createConversation">新建</n-button>
      </div>

      <button
        v-for="conversation in conversations"
        :key="conversation.id"
        type="button"
        class="conversation-item"
        :class="{ active: conversation.id === activeConversationId }"
        @click="activeConversationId = conversation.id"
      >
        <strong>{{ conversation.title }}</strong>
        <span>{{ conversation.updatedAt }}</span>
      </button>
    </section>

    <section class="message-pane">
      <div class="pane-header">
        <div>
          <h2>对话工作区</h2>
          <p>当前仅建立 SSE 连接入口，并将消息片段持续追加到助手回复。</p>
        </div>
        <n-tag :type="streaming ? 'success' : 'default'">{{ streaming ? '连接中' : '未连接' }}</n-tag>
      </div>

      <div class="message-list">
        <article
          v-for="messageItem in currentMessages"
          :key="messageItem.id"
          class="message-item"
          :class="messageItem.role"
        >
          <span class="message-role">{{ messageItem.role === 'user' ? '我' : '助手' }}</span>
          <p>{{ messageItem.content || '...' }}</p>
        </article>
      </div>

      <div class="composer">
        <n-input
          v-model:value="prompt"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 6 }"
          placeholder="请输入你的问题"
          @keydown.enter.exact.prevent="handleSend"
        />
        <div class="composer-actions">
          <span class="composer-hint">按 Enter 发送，Shift + Enter 换行</span>
          <n-button type="primary" :loading="streaming" @click="handleSend">发送</n-button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useMessage, NButton, NInput, NTag } from 'naive-ui'

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

const message = useMessage()
const authStore = useAuthStore()
const prompt = ref('')
const streaming = ref(false)
const activeConversationId = ref('conv-1')
const eventSourceRef = ref<EventSource | null>(null)

const conversations = ref<ConversationItem[]>([
  { id: 'conv-1', title: '初始化欢迎会话', updatedAt: '刚刚更新' },
  { id: 'conv-2', title: '产品问答草稿', updatedAt: '今天 10:20' }
])

const messageMap = ref<Record<string, ChatMessageItem[]>>({
  'conv-1': [
    {
      id: 'assistant-initial',
      role: 'assistant',
      content: '你好，这里是 Agent Hub 用户端初始化聊天页。'
    }
  ],
  'conv-2': []
})

const currentMessages = computed(() => messageMap.value[activeConversationId.value] ?? [])

function ensureConversationMessages(conversationId: string) {
  if (!messageMap.value[conversationId]) {
    messageMap.value[conversationId] = []
  }

  return messageMap.value[conversationId]
}

function createConversation() {
  const nextId = `conv-${Date.now()}`
  conversations.value.unshift({
    id: nextId,
    title: `新会话 ${conversations.value.length + 1}`,
    updatedAt: '刚刚创建'
  })
  messageMap.value[nextId] = []
  activeConversationId.value = nextId
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
    item.id === conversationId ? { ...item, updatedAt: '刚刚更新' } : item
  )

  const currentPrompt = trimmedPrompt
  prompt.value = ''
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

onBeforeUnmount(() => {
  closeStream()
})
</script>

<style scoped>
.chat-page {
  display: grid;
  grid-template-columns: minmax(260px, 320px) minmax(0, 1fr);
  gap: 16px;
  min-height: calc(100vh - 144px);
}

.conversation-pane,
.message-pane {
  display: grid;
  align-content: start;
  gap: 16px;
  padding: 20px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
}

.pane-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.pane-header h2 {
  margin: 0;
  color: #0f172a;
  font-size: 18px;
}

.pane-header p {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.5;
}

.conversation-item {
  display: grid;
  gap: 6px;
  width: 100%;
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  text-align: left;
  cursor: pointer;
}

.conversation-item.active {
  border-color: #3b82f6;
  background: #eff6ff;
}

.conversation-item span {
  color: #64748b;
  font-size: 12px;
}

.message-list {
  display: grid;
  gap: 12px;
  min-height: 420px;
  max-height: calc(100vh - 360px);
  overflow: auto;
  padding-right: 4px;
}

.message-item {
  display: grid;
  gap: 8px;
  max-width: min(720px, 100%);
  padding: 14px 16px;
  border-radius: 8px;
}

.message-item.user {
  justify-self: end;
  background: #dbeafe;
}

.message-item.assistant {
  background: #f8fafc;
}

.message-role {
  color: #475569;
  font-size: 12px;
  font-weight: 600;
}

.message-item p {
  margin: 0;
  color: #0f172a;
  line-height: 1.7;
  white-space: pre-wrap;
}

.composer {
  display: grid;
  gap: 12px;
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.composer-hint {
  color: #64748b;
  font-size: 12px;
}

@media (max-width: 1100px) {
  .chat-page {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .composer-actions,
  .pane-header {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
