export type OperationTagType = 'default' | 'error' | 'primary' | 'info' | 'success' | 'warning'

export function buildOperationStatusTagType(status?: string | null): OperationTagType {
  if (status === 'SUCCESS') {
    return 'success'
  }
  if (status === 'FAILED') {
    return 'error'
  }
  return 'default'
}

export function formatOperationStatus(status?: string | null): string {
  if (status === 'SUCCESS') {
    return '成功'
  }
  if (status === 'FAILED') {
    return '失败'
  }
  return status || '--'
}

export function normalizeLogLimit(value?: number | null): number {
  if (!value || Number.isNaN(value) || value <= 0) {
    return 100
  }
  return Math.min(500, Math.floor(value))
}

export function formatJsonPreview(value?: string | null): string {
  if (!value) {
    return '--'
  }

  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

export function summarizeOperationTarget(targetType?: string | null, targetId?: string | null): string {
  if (targetType && targetId) {
    return `${targetType} / ${targetId}`
  }
  return targetType || targetId || '--'
}

export function formatOperationDuration(milliseconds?: number | null): string {
  if (milliseconds === undefined || milliseconds === null || Number.isNaN(milliseconds)) {
    return '--'
  }
  if (milliseconds < 1000) {
    return `${Math.max(0, milliseconds)}ms`
  }
  return `${(milliseconds / 1000).toFixed(2)}s`
}

export function formatOperationDateTime(value?: string | null): string {
  if (!value) {
    return '--'
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return date.toLocaleString('zh-CN', { hour12: false })
}
