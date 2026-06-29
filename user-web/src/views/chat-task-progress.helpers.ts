import type { ChatTaskProgressRecord, ChatTaskStatus, ChatTaskStepStatus } from '../api/chat'

export interface NormalizedTaskStepRecord {
  stepIndex: number
  name: string
  status: ChatTaskStepStatus
  statusText: string
  errorMessage: string
}

export interface NormalizedTaskProgress {
  taskRunId: number
  mode: string
  status: ChatTaskStatus
  title: string
  planSummary: string
  currentStepIndex: number
  totalSteps: number
  revision: number
  errorMessage: string
  steps: NormalizedTaskStepRecord[]
}

export function normalizeTaskProgress(progress: ChatTaskProgressRecord): NormalizedTaskProgress {
  const steps = Array.isArray(progress.steps) ? progress.steps : []

  return {
    taskRunId: numberOf(progress.taskRunId),
    mode: textOf(progress.mode),
    status: (textOf(progress.status) || 'PLANNING') as ChatTaskStatus,
    title: textOf(progress.title) || '任务进度',
    planSummary: textOf(progress.planSummary),
    currentStepIndex: nonNegativeNumberOf(progress.currentStepIndex),
    totalSteps: nonNegativeNumberOf(progress.totalSteps),
    revision: numberOf(progress.revision),
    errorMessage: textOf(progress.errorMessage),
    steps: steps
      .map((step, index) => {
        const stepIndex = numberOf(step?.stepIndex, index + 1)
        const status = (textOf(step?.status) || 'PENDING') as ChatTaskStepStatus
        return {
          stepIndex,
          name: textOf(step?.name) || `步骤 ${stepIndex}`,
          status,
          statusText: taskStepStatusText(status),
          errorMessage: textOf(step?.errorMessage)
        }
      })
      .sort((left, right) => left.stepIndex - right.stepIndex)
  }
}

export function buildTaskProgressText(progress: ChatTaskProgressRecord | null | undefined): string {
  if (!progress) {
    return ''
  }

  const normalized = normalizeTaskProgress(progress)
  if (normalized.status === 'PLANNING') {
    return '任务进度：正在生成任务计划...'
  }
  if (normalized.status === 'COMPLETED') {
    return `任务进度：已完成 ${normalized.totalSteps} / ${normalized.totalSteps}`
  }
  if (normalized.status === 'FAILED') {
    return `任务进度：执行失败 ${normalized.currentStepIndex} / ${normalized.totalSteps}`
  }
  if (normalized.status === 'STOPPED') {
    return `任务进度：已停止 ${normalized.currentStepIndex} / ${normalized.totalSteps}`
  }
  return `任务进度：正在执行 ${normalized.currentStepIndex} / ${normalized.totalSteps}`
}

export function taskStepStatusText(status: ChatTaskStepStatus): string {
  switch (status) {
    case 'RUNNING':
      return '进行中'
    case 'COMPLETED':
      return '已完成'
    case 'FAILED':
      return '失败'
    case 'STOPPED':
      return '已停止'
    case 'SKIPPED':
      return '已跳过'
    default:
      return '等待中'
  }
}

export function taskStepDisplayStatus(
  taskStatus: ChatTaskStatus,
  stepStatus: ChatTaskStepStatus,
  stepDisplayIndex: number
): ChatTaskStepStatus {
  if (taskStatus === 'PLANNING' && stepDisplayIndex === 0 && stepStatus === 'PENDING') {
    return 'RUNNING'
  }

  return stepStatus
}

function numberOf(value: unknown, fallback = 0): number {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : fallback
}

function nonNegativeNumberOf(value: unknown, fallback = 0): number {
  return Math.max(0, numberOf(value, fallback))
}

function textOf(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  return String(value).trim()
}
