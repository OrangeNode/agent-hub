<template>
  <section class="task-progress-panel" :class="panelStatusClass" aria-label="任务进度">
    <button
      type="button"
      class="task-progress-toggle"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <span class="task-progress-state" :class="summaryStatusClass" aria-hidden="true">
        <span v-if="summaryVisualStatus === 'RUNNING'" class="task-progress-spinner"></span>
        <span v-else class="task-progress-dot" :class="summaryStatusClass"></span>
      </span>

      <span class="task-progress-main">
        <span class="task-progress-title">{{ summaryText }}</span>
        <span v-if="subtitleText" class="task-progress-subtitle">{{ subtitleText }}</span>
      </span>

      <span class="task-progress-meter" aria-hidden="true">
        <span :style="{ width: `${progressPercent}%` }"></span>
      </span>

      <svg class="task-progress-chevron" viewBox="0 0 24 24" aria-hidden="true">
        <path d="m8 10 4 4 4-4" />
      </svg>
    </button>

    <div v-if="expanded" class="task-progress-timeline">
      <div
        v-for="step in displaySteps"
        :key="step.stepIndex"
        class="task-progress-step"
        :class="stepStatusClass(step.status)"
      >
        <span class="task-progress-icon" aria-hidden="true">
          <span v-if="step.status === 'RUNNING'" class="task-progress-spinner" aria-hidden="true"></span>
          <span v-else class="task-progress-dot" :class="stepStatusClass(step.status)" aria-hidden="true"></span>
        </span>
        <span class="task-progress-copy">
          <span class="task-progress-name">{{ step.name }}</span>
          <span v-if="step.errorMessage" class="task-progress-note error">{{ step.errorMessage }}</span>
          <span v-else-if="step.status === 'RUNNING'" class="task-progress-note">当前步骤</span>
        </span>
        <span class="task-progress-badge">
          {{ step.statusText }}
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
  taskStepDisplayStatus,
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
    return normalizedProgress.value.steps.map((step, index) => {
      const status = taskStepDisplayStatus(normalizedProgress.value.status, step.status, index)
      return {
        ...step,
        status,
        statusText: taskStepStatusText(status)
      }
    })
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
const activeStep = computed(() => {
  const steps = displaySteps.value
  return steps.find((step) => step.status === 'RUNNING')
    ?? steps.find((step) => step.status === 'FAILED' || step.status === 'STOPPED')
    ?? steps.find((step) => step.status !== 'PENDING')
    ?? steps[0]
})
const subtitleText = computed(() => {
  const message = normalizedProgress.value.errorMessage
  if ((normalizedProgress.value.status === 'FAILED' || normalizedProgress.value.status === 'STOPPED') && message) {
    return message
  }

  return activeStep.value?.name || normalizedProgress.value.title
})
const progressPercent = computed(() => {
  const total = normalizedProgress.value.totalSteps
  if (normalizedProgress.value.status === 'COMPLETED') {
    return 100
  }
  if (total <= 0) {
    return 0
  }

  return Math.max(0, Math.min(100, Math.round((normalizedProgress.value.currentStepIndex / total) * 100)))
})
const summaryVisualStatus = computed<ChatTaskStepStatus>(() => taskStatusToVisualStatus(normalizedProgress.value.status))
const summaryStatusClass = computed(() => stepStatusClass(summaryVisualStatus.value))
const panelStatusClass = computed(() => `is-task-${normalizedProgress.value.status.toLowerCase()}`)

function taskStatusToStepStatus(status: ChatTaskStatus): ChatTaskStepStatus {
  if (status === 'PLANNING') {
    return 'RUNNING'
  }
  if (status === 'RUNNING' || status === 'COMPLETED' || status === 'FAILED' || status === 'STOPPED') {
    return status
  }

  return 'PENDING'
}

function taskStatusToVisualStatus(status: ChatTaskStatus): ChatTaskStepStatus {
  if (status === 'PLANNING') {
    return 'RUNNING'
  }
  return taskStatusToStepStatus(status)
}

function stepStatusClass(status: ChatTaskStepStatus): string {
  return `is-${status.toLowerCase()}`
}
</script>

<style scoped>
.task-progress-panel {
  width: min(100%, var(--chat-column-width, 770px));
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.82);
  color: #344054;
  font-size: 13px;
  overflow: hidden;
}

.task-progress-toggle {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 44px;
  padding: 9px 12px;
  border: 0;
  background: linear-gradient(180deg, #ffffff 0%, #fafbfc 100%);
  color: inherit;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.task-progress-toggle:hover {
  background: #f9fafb;
}

.task-progress-toggle:focus-visible {
  outline: 2px solid #bfdbfe;
  outline-offset: -3px;
}

.task-progress-state,
.task-progress-icon {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 999px;
}

.task-progress-state {
  background: #eff6ff;
  color: #2563eb;
}

.task-progress-state.is-completed {
  background: #ecfdf3;
}

.task-progress-state.is-failed {
  background: #fef2f2;
}

.task-progress-state.is-stopped {
  background: #f1f5f9;
}

.task-progress-state.is-pending,
.task-progress-state.is-skipped {
  background: #f8fafc;
}

.task-progress-main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.task-progress-title {
  min-width: 0;
  overflow: hidden;
  color: #1f2937;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-progress-subtitle {
  overflow: hidden;
  color: #667085;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-progress-meter {
  width: 92px;
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: #edf2f7;
}

.task-progress-meter span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #2563eb;
}

.is-task-completed .task-progress-meter span {
  background: #16a34a;
}

.is-task-failed .task-progress-meter span {
  background: #dc2626;
}

.is-task-stopped .task-progress-meter span {
  background: #64748b;
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

.task-progress-timeline {
  display: grid;
  border-top: 1px solid #edf0f3;
  background: #ffffff;
  padding: 6px 12px 12px;
}

.task-progress-step {
  position: relative;
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  min-height: 44px;
  padding: 9px 0;
}

.task-progress-step::before {
  position: absolute;
  top: 28px;
  bottom: -12px;
  left: 11px;
  width: 1px;
  background: #e5e7eb;
  content: "";
}

.task-progress-step:last-child::before {
  display: none;
}

.task-progress-icon {
  position: relative;
  z-index: 1;
  border: 1px solid #e4e7ec;
  background: #f2f4f7;
}

.task-progress-step.is-running .task-progress-icon {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.task-progress-step.is-completed .task-progress-icon {
  border-color: #bbf7d0;
  background: #ecfdf3;
}

.task-progress-step.is-failed .task-progress-icon {
  border-color: #fecaca;
  background: #fef2f2;
}

.task-progress-step.is-stopped .task-progress-icon {
  border-color: #d8dee8;
  background: #f8fafc;
}

.task-progress-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.task-progress-name {
  display: -webkit-box;
  overflow: hidden;
  color: #1f2937;
  font-size: 13px;
  font-weight: 540;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.task-progress-note {
  overflow: hidden;
  color: #8a94a6;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-progress-note.error {
  color: #b91c1c;
}

.task-progress-badge {
  align-self: center;
  min-width: 54px;
  padding: 3px 8px;
  border-radius: 999px;
  background: #f2f4f7;
  color: #667085;
  font-size: 12px;
  line-height: 1.4;
  text-align: center;
  white-space: nowrap;
}

.task-progress-step.is-running .task-progress-badge {
  background: #eff6ff;
  color: #2563eb;
}

.task-progress-step.is-completed .task-progress-badge {
  background: #ecfdf3;
  color: #15803d;
}

.task-progress-step.is-failed .task-progress-badge {
  background: #fef2f2;
  color: #b91c1c;
}

.task-progress-step.is-stopped .task-progress-badge {
  background: #f1f5f9;
  color: #475569;
}

.task-progress-step.is-skipped .task-progress-badge {
  background: #fff7ed;
  color: #c2410c;
}

.task-progress-spinner {
  width: 14px;
  height: 14px;
  flex: 0 0 auto;
  border: 2px solid #bfdbfe;
  border-top-color: #2563eb;
  border-radius: 999px;
  animation: task-progress-spin 0.8s linear infinite;
}

.task-progress-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: #cbd5e1;
}

.task-progress-dot.is-completed {
  background: #16a34a;
}

.task-progress-dot.is-failed {
  background: #dc2626;
}

.task-progress-dot.is-stopped {
  background: #64748b;
}

.task-progress-dot.is-skipped {
  background: #d97706;
}

.task-progress-dot.is-pending {
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
  .task-progress-toggle {
    grid-template-columns: auto minmax(0, 1fr) auto;
  }

  .task-progress-meter {
    display: none;
  }

  .task-progress-step {
    grid-template-columns: 24px minmax(0, 1fr);
  }

  .task-progress-badge {
    grid-column: 2;
    justify-self: start;
    margin-top: -4px;
  }
}
</style>
