<template>
  <n-modal v-model:show="show" to="body" transform-origin="center">
    <section class="history-image-modal" role="dialog" aria-modal="true" aria-labelledby="history-image-title">
      <header class="history-image-header">
        <div>
          <h3 id="history-image-title">选择历史图片</h3>
          <span>已选 {{ selectedIds.length }} 张</span>
        </div>
        <n-button quaternary size="small" @click="show = false">完成</n-button>
      </header>
      <div class="history-image-grid">
        <button
          v-for="attachment in attachments"
          :key="attachment.attachmentId"
          type="button"
          class="history-image-option"
          :class="{ selected: selectedIds.includes(attachment.attachmentId) }"
          @click="emit('toggle', attachment.attachmentId)"
        >
          <img :src="attachment.previewUrl ?? ''" :alt="attachment.filename || '历史图片'" />
          <span v-if="selectedIds.includes(attachment.attachmentId)">已选择</span>
        </button>
      </div>
    </section>
  </n-modal>
</template>

<script setup lang="ts">
import { NButton, NModal } from 'naive-ui'

import type { ChatAttachmentItem } from './chat-types'

const show = defineModel<boolean>('show', { required: true })

defineProps<{
  attachments: ChatAttachmentItem[]
  selectedIds: number[]
}>()

const emit = defineEmits<{
  toggle: [attachmentId: number]
}>()
</script>

<style scoped src="./chat-controls.css"></style>

<style scoped>
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
</style>
