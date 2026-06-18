import type { PageResult, SandboxOperation } from '../types/sandbox-admin'

export type TagType = 'default' | 'error' | 'primary' | 'info' | 'success' | 'warning'

const statusLabels: Record<string, string> = {
  ACTIVE: '活跃',
  IDLE: '空闲',
  STARTING: '启动中',
  CLOSED: '已关闭',
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  SUCCESS: '成功',
  COMPLETED: '完成',
  FAILED: '失败',
  ERROR: '异常',
  TIMEOUT: '超时',
  ORPHAN_RUN_DIR: '残留目录',
  ORPHAN_PROCESS: '残留进程',
  ORPHAN_TAP: '残留网卡',
  UNKNOWN_PROCESS: '未知进程'
}

export function buildRiskTagType(level: string): TagType {
  if (level === 'CRITICAL') {
    return 'error'
  }
  if (level === 'WARN') {
    return 'warning'
  }
  if (level === 'INFO') {
    return 'info'
  }
  return 'default'
}

export function buildStatusTagType(status?: string | null): TagType {
  if (!status) {
    return 'default'
  }

  if (['FAILED', 'ERROR', 'TIMEOUT', 'ORPHAN_RUN_DIR', 'ORPHAN_PROCESS', 'ORPHAN_TAP'].includes(status)) {
    return 'error'
  }
  if (['STARTING', 'RUNNING', 'IDLE', 'UNKNOWN_PROCESS'].includes(status)) {
    return 'warning'
  }
  if (['ACTIVE', 'SUCCEEDED', 'SUCCESS', 'COMPLETED'].includes(status)) {
    return 'success'
  }
  if (status === 'CLOSED') {
    return 'info'
  }
  return 'default'
}

export function formatStatus(status?: string | null): string {
  if (!status) {
    return '--'
  }
  return statusLabels[status] ?? status
}

export function formatBytes(value?: number | null): string {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '--'
  }

  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = Math.max(0, value)
  let unitIndex = 0

  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex += 1
  }

  if (unitIndex === 0) {
    return `${size} ${units[unitIndex]}`
  }
  return `${size.toFixed(2)} ${units[unitIndex]}`
}

export function formatDurationSeconds(seconds?: number | null): string {
  if (seconds === undefined || seconds === null || Number.isNaN(seconds)) {
    return '--'
  }

  const total = Math.max(0, Math.floor(seconds))
  const days = Math.floor(total / 86400)
  const hours = Math.floor((total % 86400) / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const restSeconds = total % 60

  if (days > 0) {
    return `${days}天${hours}小时`
  }
  if (hours > 0) {
    return `${hours}小时${minutes}分`
  }
  if (minutes > 0) {
    return `${minutes}分${restSeconds}秒`
  }
  return `${restSeconds}秒`
}

export function formatDurationMs(milliseconds?: number | null): string {
  if (milliseconds === undefined || milliseconds === null || Number.isNaN(milliseconds)) {
    return '--'
  }
  if (milliseconds < 1000) {
    return `${Math.max(0, milliseconds)}ms`
  }
  return formatDurationSeconds(milliseconds / 1000)
}

export function formatDateTime(value?: string | null): string {
  if (!value) {
    return '--'
  }

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }

  return date.toLocaleString('zh-CN', { hour12: false })
}

export function requiresStrongConfirm(action: SandboxOperation): boolean {
  return ['KILL_VM', 'CLEANUP_ORPHANS', 'CLEAR_RUN_DIR', 'REBUILD_WARM_POOL'].includes(action)
}

export function requiresReason(action: SandboxOperation): boolean {
  return requiresStrongConfirm(action)
}

export function expectedConfirmText(action: SandboxOperation, targetId?: string | null): string {
  if (action === 'KILL_VM') {
    return targetId || '终止VM'
  }
  if (action === 'CLOSE_SESSION') {
    return targetId || '关闭会话'
  }
  if (action === 'CLOSE_MISSING_RUNTIME') {
    return '关闭缺失运行态会话'
  }
  if (action === 'CLEAR_RUN_DIR') {
    return '清空运行目录'
  }
  if (action === 'CLEANUP_ORPHANS') {
    return '清理残留资源'
  }
  if (action === 'REBUILD_WARM_POOL') {
    return '重建预热池'
  }
  return '回收空闲运行时'
}

export function buildOperationTitle(action: SandboxOperation, targetId?: string | null): string {
  if (action === 'KILL_VM') {
    return `终止 VM${targetId ? `：${targetId}` : ''}`
  }
  if (action === 'CLOSE_SESSION') {
    return `关闭会话${targetId ? `：${targetId}` : ''}`
  }
  if (action === 'CLOSE_MISSING_RUNTIME') {
    return '关闭缺失运行态会话'
  }
  if (action === 'CLEAR_RUN_DIR') {
    return '清空运行目录'
  }
  if (action === 'CLEANUP_ORPHANS') {
    return '清理残留资源'
  }
  if (action === 'REBUILD_WARM_POOL') {
    return '重建预热池'
  }
  return '回收空闲运行时'
}

export function canSubmitOperation(
  action: SandboxOperation,
  reason: string,
  confirmText: string,
  targetId?: string | null
): boolean {
  if (requiresReason(action) && !reason.trim()) {
    return false
  }

  if (requiresStrongConfirm(action)) {
    return confirmText.trim() === expectedConfirmText(action, targetId)
  }

  return true
}

export function buildControlRequest(
  action: SandboxOperation,
  reason: string,
  confirmText: string,
  dryRun: boolean,
  targetId?: string | null
) {
  return {
    reason: reason.trim(),
    confirmText: requiresStrongConfirm(action) ? confirmText.trim() : expectedConfirmText(action, targetId),
    dryRun
  }
}

export function buildPageQuery(page: number, pageSize: number) {
  const safePage = Math.max(1, Math.floor(page))
  const safePageSize = Math.max(1, Math.floor(pageSize))
  return {
    limit: safePageSize,
    offset: (safePage - 1) * safePageSize
  }
}

export function normalizePageResult<T>(
  result: PageResult<T> | T[] | null | undefined,
  limit: number,
  offset: number
): PageResult<T> {
  if (Array.isArray(result)) {
    return {
      items: result,
      total: result.length,
      limit,
      offset
    }
  }

  if (!result) {
    return {
      items: [],
      total: 0,
      limit,
      offset
    }
  }

  const page = result as Partial<PageResult<T>>
  const items = Array.isArray(page.items) ? page.items : []
  return {
    items,
    total: typeof page.total === 'number' ? page.total : items.length,
    limit: typeof page.limit === 'number' ? page.limit : limit,
    offset: typeof page.offset === 'number' ? page.offset : offset
  }
}
