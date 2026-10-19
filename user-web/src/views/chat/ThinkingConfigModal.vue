<template>
  <n-modal
    v-model:show="show"
    class="thinking-config-modal"
    to="body"
    transform-origin="center"
  >
    <section class="thinking-config-dialog" role="dialog" aria-modal="true" aria-labelledby="thinking-config-title">
      <header class="thinking-config-header">
        <h3 id="thinking-config-title">思考时长</h3>
        <button type="button" class="thinking-config-close" aria-label="关闭思考时长配置" @click="show = false">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      </header>
      <div class="config-row">
        <span>模型</span>
        <n-select
          v-model:value="selectedModel"
          :options="modelOptions"
          :bordered="false"
          size="small"
          class="config-select"
        />
      </div>
      <div class="config-divider" />
      <button type="button" class="mode-option" :class="{ active: thinkingMode === 'instant' }" @click="emit('select-mode', 'instant')">
        <strong>Instant</strong>
        <span>适用于日常聊天</span>
      </button>
      <button type="button" class="mode-option" :class="{ active: thinkingMode === 'thinking' }" @click="emit('select-mode', 'thinking')">
        <strong>Thinking</strong>
        <span>适用于解答复杂问题</span>
      </button>
    </section>
  </n-modal>
</template>

<script setup lang="ts">
import { NModal, NSelect } from 'naive-ui'

import type { ThinkingMode } from './chat-types'

const show = defineModel<boolean>('show', { required: true })
const selectedModel = defineModel<number | null>('selectedModel', { required: true })

defineProps<{
  modelOptions: Array<{ label: string, value: number }>
  thinkingMode: ThinkingMode
}>()

const emit = defineEmits<{
  'select-mode': [mode: ThinkingMode]
}>()
</script>

<style scoped src="./chat-controls.css"></style>

<style scoped>
.thinking-config-dialog {
  display: grid;
  width: min(450px, calc(100vw - 32px));
  max-width: calc(100vw - 32px);
  padding: 16px;
  border: 1px solid #d9d9d9;
  border-radius: 18px;
  background: #ffffff;
  box-shadow: 0 18px 44px rgba(15, 23, 42, 0.16);
}

.thinking-config-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 22px;
}

.thinking-config-header h3 {
  margin: 0;
  color: #111827;
  font-size: 19px;
  font-weight: 500;
  line-height: 1.25;
}

.thinking-config-close {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #111827;
  cursor: pointer;
}

.thinking-config-close:hover {
  background: #f3f4f6;
}

.thinking-config-close:focus-visible {
  outline: 2px solid #d1d5db;
  outline-offset: 2px;
}

.thinking-config-close svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
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
  --n-height: 30px;
}

.config-select :deep(.n-base-selection) {
  justify-content: flex-end;
}

.config-select :deep(.n-base-selection-label) {
  padding-right: 0;
  background: transparent;
}

.config-select :deep(.n-base-selection-input) {
  text-align: right;
}

.config-select :deep(.n-base-selection__border),
.config-select :deep(.n-base-selection__state-border) {
  display: none;
}

.config-divider {
  height: 1px;
  background: #e5e7eb;
}

.mode-option {
  position: relative;
  display: grid;
  gap: 4px;
  padding: 12px 34px 12px 0;
  border-radius: 8px;
  text-align: left;
}

.mode-option:hover {
  background: #f7f7f8;
}

.mode-option strong {
  color: #111827;
  font-size: 15px;
  font-weight: 500;
}

.mode-option span {
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
</style>
