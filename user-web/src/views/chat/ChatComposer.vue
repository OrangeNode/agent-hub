<template>
  <!-- display: contents 让附件列表和输入框仍作为父容器的网格项排布，与拆分前布局一致。 -->
  <div class="chat-composer">
    <div
      v-if="composerAttachmentCount"
      class="pending-image-list"
      :class="{ single: composerAttachmentCount === 1 }"
    >
      <div
        v-for="image in pendingImages"
        :key="image.localId"
        class="pending-image-item"
      >
        <button
          type="button"
          class="image-preview-button"
          :class="{ file: image.kind === 'file' }"
          @click="image.kind === 'image' && emit('preview-image', image.previewUrl)"
        >
          <img v-if="image.kind === 'image'" :src="image.previewUrl ?? ''" :alt="image.file.name" />
          <template v-else>
            <span class="pending-file-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <path d="M14 2v6h6" />
                <path d="M8 13h8M8 17h6" />
              </svg>
            </span>
            <span class="pending-file-name">{{ image.file.name }}</span>
          </template>
          <span v-if="image.status === 'uploading'" class="image-progress">{{ image.progress }}%</span>
          <span v-if="image.status === 'failed'" class="image-error">上传失败</span>
        </button>
        <button
          type="button"
          class="image-remove-button"
          aria-label="移除图片"
          @click.stop="emit('remove-pending', image.localId)"
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
          @click="emit('preview-image', attachment.previewUrl ?? null)"
        >
          <img :src="attachment.previewUrl ?? ''" :alt="attachment.filename || '历史图片'" />
          <span class="image-reuse-badge">已选</span>
        </button>
        <button
          type="button"
          class="image-remove-button"
          aria-label="移除历史图片"
          @click.stop="emit('remove-reuse', attachment.attachmentId)"
        >
          ×
        </button>
      </div>
    </div>

    <slot name="before-input" />

    <div class="landing-composer" :class="{ compact, 'is-multiline': isMultiline }">
      <n-dropdown
        trigger="click"
        placement="top-start"
        :options="uploadMenuOptions"
        @select="(key: string | number) => emit('upload-menu-select', key)"
      >
        <button type="button" class="round-action composer-upload" aria-label="添加附件">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </button>
      </n-dropdown>
      <span v-if="activeAgentKey" class="agent-tag composer-agent">
        {{ activeAgentName }}
        <button v-if="!activeAgentLocked" type="button" class="agent-tag-close" @click="emit('clear-agent')">&times;</button>
      </span>
      <template v-else>
        <span
          v-for="skill in selectedSkillItems"
          :key="skill.skillId"
          class="skill-tag composer-skill"
        >
          {{ skill.name }}
          <button type="button" class="skill-tag-close" @click="emit('remove-skill', skill.skillId)">&times;</button>
        </span>
      </template>
      <n-input
        v-model:value="prompt"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 5 }"
        placeholder="有问题，尽管问"
        @keydown.enter.exact.prevent="emit('send')"
        class="composer-input"
      />
      <ThinkingModeSelect
        class="composer-model"
        :mode="thinkingMode"
        :model-label="selectedModelLabel"
        @select="(mode: ThinkingMode) => emit('select-thinking-mode', mode)"
        @configure="emit('configure-thinking')"
      />
      <button
        type="button"
        class="send-orb composer-send"
        :class="{ 'is-stopping': streaming }"
        :disabled="sendButtonDisabled"
        :aria-label="streaming ? '中止生成' : '发送'"
        :title="streaming ? '中止生成' : '发送'"
        @click="emit('send')"
      >
        <svg v-if="streaming" viewBox="0 0 24 24" aria-hidden="true">
          <rect x="5" y="5" width="14" height="14" rx="2.5" />
        </svg>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 12h14M13 6l6 6-6 6" />
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NDropdown, NInput } from 'naive-ui'

import type { AvailableSkillRecord } from '../../api/chat'
import ThinkingModeSelect from '../ThinkingModeSelect.vue'
import type { ChatAttachmentItem, PendingImage, ThinkingMode, UploadMenuOption } from './chat-types'

const prompt = defineModel<string>('prompt', { required: true })

const props = defineProps<{
  compact?: boolean
  pendingImages: PendingImage[]
  selectedReuseAttachments: ChatAttachmentItem[]
  uploadMenuOptions: UploadMenuOption[]
  activeAgentKey: string | null
  activeAgentName: string
  activeAgentLocked: boolean
  selectedSkillItems: AvailableSkillRecord[]
  thinkingMode: ThinkingMode
  selectedModelLabel: string
  streaming: boolean
  sendButtonDisabled: boolean
}>()

const emit = defineEmits<{
  send: []
  'preview-image': [url: string | null]
  'remove-pending': [localId: string]
  'remove-reuse': [attachmentId: number]
  'upload-menu-select': [key: string | number]
  'clear-agent': []
  'remove-skill': [skillId: number]
  'select-thinking-mode': [mode: ThinkingMode]
  'configure-thinking': []
}>()

const composerAttachmentCount = computed(() => props.pendingImages.length + props.selectedReuseAttachments.length)
const isMultiline = computed(() => prompt.value.includes('\n') || prompt.value.length > 40)
</script>

<style scoped src="./chat-controls.css"></style>

<style scoped>
.chat-composer {
  display: contents;
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
.composer-skill { order: 2; }
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
.landing-composer.is-multiline .composer-skill { order: 3; }
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
.composer-skill,
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

.send-orb {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 999px;
  background: #2563eb;
  color: #ffffff;
}

.send-orb.is-stopping {
  background: #2563eb;
}

.send-orb:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.send-orb svg {
  width: 18px;
  height: 18px;
}

.send-orb.is-stopping rect {
  fill: currentColor;
  stroke: none;
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

.image-preview-button.file {
  display: grid;
  align-content: center;
  justify-items: center;
  gap: 4px;
  padding: 6px;
  cursor: default;
}

.pending-file-icon {
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  color: #4b5563;
}

.pending-file-icon svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.pending-file-name {
  display: block;
  width: 100%;
  overflow: hidden;
  color: #374151;
  font-size: 10px;
  line-height: 1.2;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.skill-tag {
  display: inline-flex;
  align-items: center;
  max-width: 180px;
  gap: 5px;
  padding: 5px 8px;
  border-radius: 8px;
  background: #ecfdf5;
  color: #065f46;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-tag-close {
  display: grid;
  width: 16px;
  height: 16px;
  flex: 0 0 auto;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #047857;
  cursor: pointer;
  font-family: Arial, Helvetica, sans-serif;
  font-size: 14px;
  line-height: 1;
}

.skill-tag-close:hover {
  background: #bbf7d0;
}

.round-action:hover {
  background: #f0f0f0;
}

@media (max-width: 640px) {
  .landing-composer {
    padding: 8px 12px;
  }
}
</style>
