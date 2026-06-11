export type OperationLogStatus = 'SUCCESS' | 'FAILED' | string

export interface OperationLogItem {
  id: number
  traceId?: string | null
  userId?: number | null
  username?: string | null
  module: string
  action: string
  targetType?: string | null
  targetId?: string | null
  operationType?: string | null
  reason?: string | null
  requestMethod?: string | null
  requestUri?: string | null
  clientIp?: string | null
  userAgent?: string | null
  paramsJson?: string | null
  resultJson?: string | null
  status: OperationLogStatus
  errorCode?: string | null
  errorMessage?: string | null
  durationMs?: number | null
  createdAt: string
}

export interface OperationLogQuery {
  startAt?: string | null
  endAt?: string | null
  username?: string | null
  module?: string | null
  action?: string | null
  status?: string | null
  targetId?: string | null
  keyword?: string | null
  limit?: number | null
}
