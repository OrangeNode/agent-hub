import http from './http'
import type { ApiResponse } from '../types/rbac'
import type { OperationLogItem, OperationLogQuery } from '../types/operation-log'

function cleanParams(params: object) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== '')
  )
}

export async function fetchOperationLogs(params: OperationLogQuery = {}) {
  const response = await http.get<ApiResponse<OperationLogItem[]>>('/system/operation-logs', {
    params: cleanParams(params)
  })
  return response.data.data
}
