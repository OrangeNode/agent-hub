<template>
  <div class="message-list">
    <article
      v-for="messageItem in messages"
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
            :class="{ disabled: attachment.expired, file: !canPreviewAttachment(attachment) }"
            :disabled="attachment.expired"
            @click="emit('preview-attachment', attachment)"
          >
            <img
              v-if="canPreviewAttachment(attachment)"
              :src="attachment.previewUrl ?? ''"
              :alt="attachment.filename || '历史图片'"
            />
            <template v-else>
              <span class="message-file-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <path d="M14 2v6h6" />
                </svg>
              </span>
              <span>{{ attachment.message || attachment.filename || '附件' }}</span>
            </template>
          </button>
        </div>
      </div>

      <div v-else class="assistant-message">
        <div
          v-if="messageItem.processEvents?.length"
          class="process-block"
          :class="{ collapsed: !messageItem.processExpanded }"
        >
          <button
            type="button"
            class="process-toggle"
            :aria-expanded="messageItem.processExpanded"
            @click="toggleProcess(messageItem)"
          >
            <span>{{ processToggleText(messageItem) }}</span>
            <small>{{ processStatusText(messageItem) }}</small>
          </button>
          <Transition name="process-expand">
            <div v-if="messageItem.processExpanded" class="process-content">
              <template
                v-for="(event, index) in messageItem.processEvents"
                :key="processEventKey(event, index)"
              >
                <div v-if="event.type === 'reasoning'" class="process-reasoning-scroll" aria-label="思考内容">
                  <p class="process-reasoning">{{ event.content }}</p>
                </div>
                <section v-else class="process-tool">
                  <button
                    type="button"
                    class="process-tool-toggle"
                    :aria-expanded="event.tool.expanded"
                    @click="toggleToolResult(event.tool)"
                  >
                    <span class="process-tool-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24">
                        <path d="M14.7 6.3a4 4 0 0 0-5.4 5.4l-5 5a2 2 0 0 0 2.8 2.8l5-5a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.6-2.6 2.4-2.8z" />
                      </svg>
                    </span>
                    <span class="process-tool-title">工具调用：{{ toolDisplayName(event.tool.name) }}</span>
                    <span class="process-tool-status">{{ toolStatusText(event.tool.status) }}</span>
                    <span class="process-tool-expand">{{ toolResultToggleText(event.tool) }}</span>
                  </button>
                  <Transition name="tool-detail">
                    <div v-if="event.tool.expanded" class="process-tool-detail">
                      <pre v-if="event.tool.stdout" class="process-tool-output">{{ event.tool.stdout }}</pre>
                      <pre v-if="event.tool.stderr" class="process-tool-output error">{{ event.tool.stderr }}</pre>
                      <p v-if="event.tool.result" class="process-tool-result">{{ event.tool.result }}</p>
                      <p
                        v-if="!event.tool.stdout && !event.tool.stderr && !event.tool.result"
                        class="process-tool-empty"
                      >
                        暂无工具结果
                      </p>
                    </div>
                  </Transition>
                </section>
              </template>
            </div>
          </Transition>
        </div>
        <div
          class="assistant-markdown"
          v-html="renderAssistantMarkdown(assistantDisplayContent(messageItem))"
        />
        <div
          v-if="shouldShowArtifactBundle(messageItem)"
          class="artifact-bundle-card"
          role="button"
          tabindex="0"
          @click="openArtifactPanel()"
          @keydown.enter.prevent="openArtifactPanel()"
          @keydown.space.prevent="openArtifactPanel()"
        >
          <span class="artifact-bundle-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H10l2 2h6.5A2.5 2.5 0 0 1 21 9.5v7A2.5 2.5 0 0 1 18.5 19h-13A2.5 2.5 0 0 1 3 16.5z" />
              <path d="M3 10h18" />
            </svg>
          </span>
          <span class="artifact-bundle-text">
            <strong>全部文件</strong>
            <small>预览或者下载文件</small>
          </span>
          <button type="button" @click.stop="openArtifactPanel()">预览</button>
        </div>
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
          <button type="button" title="来源" @click="emit('open-sources')">
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
</template>

<script setup lang="ts">
import { useMessage } from 'naive-ui'

import { assistantDisplayContent } from '../chat-view.helpers'
import type { ChatProcessEvent, ChatToolTrace } from '../chat-tool-events.helpers'
import { renderAssistantMarkdown } from '../message-render.helpers'
import { buildReasoningStatusText } from '../reasoning-timer.helpers'
import type { ChatAttachmentItem, ChatMessageItem } from './chat-types'
import { canPreviewAttachment } from './useChatAttachments'

const props = defineProps<{
  messages: ChatMessageItem[]
  hasArtifacts: boolean
  reasoningNow: number
}>()

const emit = defineEmits<{
  'preview-attachment': [attachment: ChatAttachmentItem]
  'open-artifacts': []
  'open-sources': []
}>()

const message = useMessage()

/**
 * 产物卡片只挂在最后一条助手消息上，避免每轮回复都重复展示“全部文件”。
 */
function shouldShowArtifactBundle(messageItem: ChatMessageItem) {
  if (messageItem.role !== 'assistant' || !props.hasArtifacts) {
    return false
  }
  const assistantMessages = props.messages.filter((item) => item.role === 'assistant')
  return assistantMessages[assistantMessages.length - 1]?.id === messageItem.id
}

function openArtifactPanel() {
  emit('open-artifacts')
}

function copyMessage(content: string) {
  navigator.clipboard.writeText(content).then(
    () => message.success('已复制到剪贴板'),
    () => message.error('复制失败')
  )
}

// 展开状态只影响展示，直接记录在消息视图对象上。
function toggleProcess(messageItem: ChatMessageItem) {
  messageItem.processExpanded = !messageItem.processExpanded
}

function processToggleText(messageItem: ChatMessageItem) {
  return messageItem.processExpanded ? '收起思考过程↑' : '展开思考过程↓'
}

function toggleToolResult(tool: ChatToolTrace) {
  tool.expanded = !tool.expanded
}

function toolResultToggleText(tool: ChatToolTrace) {
  return tool.expanded ? '收起结果↑' : '展开结果↓'
}

function processStatusText(messageItem: ChatMessageItem) {
  const elapsedMs = reasoningElapsedMs(messageItem)

  if (elapsedMs === null) {
    if (messageItem.status === 'STOPPED') {
      return '已中止'
    }

    return messageItem.status === 'STREAMING' ? '处理中' : '处理过程'
  }

  return buildReasoningStatusText(elapsedMs, Boolean(messageItem.reasoningFinishedAt))
}

function reasoningElapsedMs(messageItem: ChatMessageItem) {
  if (!messageItem.reasoningStartedAt) {
    return null
  }

  return Math.max(0, (messageItem.reasoningFinishedAt ?? props.reasoningNow) - messageItem.reasoningStartedAt)
}

function processEventKey(event: ChatProcessEvent, index: number) {
  return event.type === 'tool' ? `tool-${event.tool.toolCallId}` : `reasoning-${index}`
}

function toolDisplayName(name: string) {
  const names: Record<string, string> = {
    exec: '执行命令',
    read_file: '读取文件',
    write_file: '写入文件',
    list_files: '浏览文件',
    upload_file: '导入文件'
  }
  return names[name] ?? name
}

function toolStatusText(status: string) {
  const statuses: Record<string, string> = {
    running: '进行中',
    completed: '已完成',
    failed: '失败',
    timeout: '超时'
  }
  return statuses[status] ?? status
}
</script>

<style scoped src="./chat-controls.css"></style>

<style scoped>
.message-list {
  flex: 1 0 auto;
  min-height: 0;
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

.message-attachment.file {
  align-content: center;
  gap: 4px;
  padding: 6px;
}

.message-file-icon {
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  padding: 0;
}

.message-file-icon svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.message-attachment span {
  padding: 6px;
  font-size: 12px;
  line-height: 1.35;
  text-align: center;
  white-space: normal;
}

.message-attachment .message-file-icon {
  padding: 0;
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

.process-block {
  display: grid;
  gap: 12px;
  margin-bottom: 18px;
  padding: 0 0 0 2px;
  border-bottom: 1px solid #edf0f3;
}

.process-block.collapsed {
  gap: 0;
}

.process-toggle {
  display: inline-flex;
  align-items: center;
  gap: 10px;
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

.process-toggle span {
  font-size: 13px;
  font-weight: 500;
}

.process-toggle small {
  color: #9ca3af;
  font-size: 12px;
  font-weight: 400;
}

.process-content {
  display: grid;
  gap: 12px;
  margin: 0 0 16px 0;
  padding-left: 14px;
  border-left: 2px solid #e5e7eb;
}

.process-expand-enter-active,
.process-expand-leave-active,
.tool-detail-enter-active,
.tool-detail-leave-active {
  overflow: hidden;
  transition: max-height 0.22s ease, opacity 0.18s ease, transform 0.22s ease;
}

.process-expand-enter-from,
.process-expand-leave-to,
.tool-detail-enter-from,
.tool-detail-leave-to {
  max-height: 0;
  opacity: 0;
  transform: translateY(-4px);
}

.process-expand-enter-to,
.process-expand-leave-from {
  max-height: 720px;
  opacity: 1;
  transform: translateY(0);
}

.tool-detail-enter-to,
.tool-detail-leave-from {
  max-height: 420px;
  opacity: 1;
  transform: translateY(0);
}

.process-reasoning-scroll {
  overflow-y: auto;
  max-height: 180px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
  scrollbar-color: #cfd4dc transparent;
  scrollbar-width: thin;
}

.process-reasoning-scroll::-webkit-scrollbar {
  width: 8px;
}

.process-reasoning-scroll::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: #cfd4dc;
}

.process-reasoning {
  margin: 0;
  color: #4b5563;
  font-size: 14px;
  line-height: 1.85;
  white-space: pre-wrap;
}

.process-tool {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.process-tool-toggle {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  min-width: 0;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  color: #374151;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  line-height: 1.4;
  text-align: left;
}

.process-tool-toggle:hover {
  border-color: #cfd4dc;
  background: #fafafa;
}

.process-tool-toggle:focus-visible {
  outline: 2px solid #d1d5db;
  outline-offset: 2px;
}

.process-tool-icon {
  display: inline-grid;
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
  place-items: center;
  color: #4f46e5;
}

.process-tool-icon svg {
  width: 16px;
  height: 16px;
}

.process-tool-title {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.process-tool-status {
  color: #6b7280;
}

.process-tool-expand {
  color: #6b7280;
  font-size: 12px;
}

.process-tool-detail {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding-left: 26px;
}

.process-tool-output {
  overflow: auto;
  max-height: 160px;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #f8fafc;
  color: #334155;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.process-tool-output.error {
  border-color: #fecaca;
  background: #fef2f2;
  color: #991b1b;
}

.process-tool-result {
  overflow-y: auto;
  max-height: 180px;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #ffffff;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
}

.process-tool-empty {
  margin: 0;
  color: #9ca3af;
  font-size: 13px;
}

@media (prefers-reduced-motion: reduce) {
  .process-expand-enter-active,
  .process-expand-leave-active,
  .tool-detail-enter-active,
  .tool-detail-leave-active {
    transition: none;
  }
}

.artifact-bundle-card {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  width: min(100%, var(--chat-column-width));
  margin: 18px 0 10px;
  padding: 12px 14px;
  border: 1px solid #d9dce3;
  border-radius: 8px;
  background: #ffffff;
  cursor: pointer;
  text-align: left;
  transition: background-color 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease;
}

.artifact-bundle-card:hover,
.artifact-bundle-card:focus-visible {
  border-color: #cfd4dc;
  background: #fafafa;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.05);
}

.artifact-bundle-card:focus-visible {
  outline: 2px solid #d1d5db;
  outline-offset: 2px;
}

.artifact-bundle-icon {
  display: grid;
  width: 42px;
  height: 34px;
  place-items: center;
  color: #6b7280;
}

.artifact-bundle-icon svg {
  width: 38px;
  height: 30px;
}

.artifact-bundle-text {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.artifact-bundle-text strong {
  overflow: hidden;
  color: #111827;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-bundle-text small {
  color: #6b7280;
  font-size: 12px;
}

.artifact-bundle-card button {
  padding: 7px 12px;
  border-radius: 8px;
  background: #f4f4f5;
  color: #111827;
  font-size: 13px;
}

.artifact-bundle-card button:hover {
  background: #e9eaee;
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

.message-tools button:hover {
  background: #f0f0f0;
}
</style>
