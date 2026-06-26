<template>
  <section class="task-progress-panel" aria-label="任务进度">
    <button
      type="button"
      class="task-progress-toggle"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <span>{{ summaryText }}</span>
      <svg class="task-progress-chevron" viewBox="0 0 24 24" aria-hidden="true">
        <path d="m8 10 4 4 4-4" />
      </svg>
    </button>

    <div v-if="expanded" class="task-progress-list">
      <div class="task-progress-row task-progress-head">
        <span>任务名称</span>
        <span>任务进度</span>
      </div>
      <div
        v-for="step in displaySteps"
        :key="step.stepIndex"
        class="task-progress-row"
      >
        <span class="task-progress-name">{{ step.name }}</span>
        <span class="task-progress-status">
          <span v-if="step.status === 'RUNNING'" class="task-progress-spinner" aria-hidden="true"></span>
          <span v-else-if="step.status === 'COMPLETED'" class="task-progress-dot completed" aria-hidden="true"></span>
          <span v-else-if="step.status === 'FAILED'" class="task-progress-dot failed" aria-hidden="true"></span>
          <span v-else-if="step.status === 'STOPPED'" class="task-progress-dot stopped" aria-hidden="true"></span>
          <span v-else-if="step.status === 'SKIPPED'" class="task-progress-dot skipped" aria-hidden="true"></span>
          <span v-else class="task-progress-dot pending" aria-hidden="true"></span>
          <span>{{ taskStepStatusText(step.status) }}</span>
        </span>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ChatTaskProgressRecord, ChatTaskStepStatus, ChatTaskStatus } from '../api/chat'
import {
  buildTaskProgressText,
  normalizeTaskProgress,
  taskStepStatusText
} from './chat-task-progress.helpers'

const props = defineProps<{
  progress: ChatTaskProgressRecord
}>()

const expanded = ref(false)
const normalizedProgress = computed(() => normalizeTaskProgress(props.progress))
const summaryText = computed(() => buildTaskProgressText(props.progress) || '任务进度')
const displaySteps = computed(() => {
  if (normalizedProgress.value.steps.length) {
    return normalizedProgress.value.steps
  }

  const status = taskStatusToStepStatus(normalizedProgress.value.status)
  return [
    {
      stepIndex: 1,
      name: normalizedProgress.value.title,
      status,
      statusText: taskStepStatusText(status),
      errorMessage: normalizedProgress.value.errorMessage
    }
  ]
})

function taskStatusToStepStatus(status: ChatTaskStatus): ChatTaskStepStatus {
  if (status === 'RUNNING' || status === 'COMPLETED' || status === 'FAILED' || status === 'STOPPED') {
    return status
  }

  return 'PENDING'
}
</script>

<style scoped>
.task-progress-panel {
  width: min(100%, var(--chat-column-width, 770px));
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  color: #374151;
  font-size: 13px;
  overflow: hidden;
}

.task-progress-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 9px 12px;
  border: 0;
  background: #ffffff;
  color: inherit;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.task-progress-toggle:hover {
  background: #fafafa;
}

.task-progress-toggle:focus-visible {
  outline: 2px solid #d1d5db;
  outline-offset: -2px;
}

.task-progress-chevron {
  width: 16px;
  height: 16px;
  flex: 0 0 auto;
  color: #6b7280;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
  transition: transform 0.16s ease;
}

.task-progress-toggle[aria-expanded="true"] .task-progress-chevron {
  transform: rotate(180deg);
}

.task-progress-list {
  display: grid;
  border-top: 1px solid #edf0f3;
}

.task-progress-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(118px, 168px);
  align-items: center;
  gap: 12px;
  min-width: 0;
  padding: 9px 12px;
}

.task-progress-row + .task-progress-row {
  border-top: 1px solid #f0f2f5;
}

.task-progress-head {
  background: #f8fafc;
  color: #6b7280;
  font-size: 12px;
  font-weight: 600;
}

.task-progress-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-progress-status {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  color: #4b5563;
  white-space: nowrap;
}

.task-progress-spinner {
  width: 12px;
  height: 12px;
  flex: 0 0 auto;
  border: 2px solid #dbe3ef;
  border-top-color: #2563eb;
  border-radius: 999px;
  animation: task-progress-spin 0.8s linear infinite;
}

.task-progress-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: #9ca3af;
}

.task-progress-dot.completed {
  background: #16a34a;
}

.task-progress-dot.failed {
  background: #dc2626;
}

.task-progress-dot.stopped {
  background: #6b7280;
}

.task-progress-dot.skipped {
  background: #d97706;
}

.task-progress-dot.pending {
  background: #cbd5e1;
}

@keyframes task-progress-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .task-progress-spinner,
  .task-progress-chevron {
    animation: none;
    transition: none;
  }
}

@media (max-width: 640px) {
  .task-progress-row {
    grid-template-columns: minmax(0, 1fr) minmax(96px, auto);
  }
}
</style>
