<template>
  <div class="thinking-menu-wrap" :class="{ open }">
    <button type="button" class="model-select thinking-trigger" @click="open = !open">
      {{ modeLabel }}
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="m6 9 6 6 6-6" />
      </svg>
    </button>
    <div class="thinking-menu">
      <span class="thinking-latest">最新 · {{ modelLabel }}</span>
      <button type="button" :class="{ active: mode === 'instant' }" @click="selectMode('instant')">
        Instant
      </button>
      <button type="button" :class="{ active: mode === 'thinking' }" @click="selectMode('thinking')">
        Thinking
      </button>
      <hr />
      <button type="button" @click="openConfig">配置...</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

type ThinkingMode = 'instant' | 'thinking'

const props = defineProps<{
  mode: ThinkingMode
  modelLabel: string
}>()

const emit = defineEmits<{
  select: [mode: ThinkingMode]
  configure: []
}>()

const open = ref(false)
const modeLabel = computed(() => props.mode === 'thinking' ? 'Thinking' : 'Instant')

function selectMode(mode: ThinkingMode) {
  emit('select', mode)
  open.value = false
}

function openConfig() {
  emit('configure')
  open.value = false
}
</script>

<style scoped>
.thinking-menu-wrap {
  position: relative;
}

.model-select,
.thinking-menu button {
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.model-select {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border-radius: 999px;
  color: #6b7280;
  font-size: 14px;
  white-space: nowrap;
}

.model-select:hover {
  background: #f0f0f0;
}

.thinking-trigger svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
}

.thinking-menu {
  position: absolute;
  z-index: 40;
  right: 0;
  bottom: calc(100% + 8px);
  display: grid;
  min-width: 148px;
  gap: 2px;
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.16);
  opacity: 0;
  pointer-events: none;
  transform: translateY(4px);
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.thinking-menu-wrap.open .thinking-menu,
.thinking-menu-wrap:focus-within .thinking-menu {
  opacity: 1;
  pointer-events: auto;
  transform: translateY(0);
}

.thinking-menu button {
  padding: 8px 6px;
  border-radius: 7px;
  text-align: left;
  font-size: 14px;
}

.thinking-menu button:hover,
.thinking-menu button.active {
  background: #f3f4f6;
  color: #111827;
}

.thinking-menu hr {
  width: 100%;
  height: 1px;
  margin: 6px 0;
  border: 0;
  background: #e5e7eb;
}

.thinking-latest {
  padding: 2px 6px 6px;
  color: #9ca3af;
  font-size: 12px;
}

@media (max-width: 640px) {
  .model-select {
    padding: 6px 8px;
    font-size: 13px;
  }
}
</style>
