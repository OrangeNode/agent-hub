import http from './http'
import type { ApiResponse } from '../types/rbac'
import type {
  SandboxAdminArtifactItem,
  SandboxAdminExecutionItem,
  SandboxAdminSessionItem,
  PageResult,
  SandboxArtifactQuery,
  SandboxControlRequest,
  SandboxControlResult,
  SandboxExecutionQuery,
  SandboxOverview,
  SandboxResourceSnapshot,
  SandboxSessionQuery
} from '../types/sandbox-admin'

const basePath = '/sandbox/admin'

function cleanParams(params: object) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== '')
  )
}

export async function fetchSandboxOverview() {
  const response = await http.get<ApiResponse<SandboxOverview>>(`${basePath}/overview`)
  return response.data.data
}

export async function fetchSandboxSessions(params: SandboxSessionQuery = {}) {
  const response = await http.get<ApiResponse<PageResult<SandboxAdminSessionItem>>>(`${basePath}/sessions`, {
    params: cleanParams(params)
  })
  return response.data.data
}

export async function fetchSandboxExecutions(params: SandboxExecutionQuery = {}) {
  const response = await http.get<ApiResponse<PageResult<SandboxAdminExecutionItem>>>(`${basePath}/executions`, {
    params: cleanParams(params)
  })
  return response.data.data
}

export async function fetchSandboxArtifacts(params: SandboxArtifactQuery = {}) {
  const response = await http.get<ApiResponse<PageResult<SandboxAdminArtifactItem>>>(`${basePath}/artifacts`, {
    params: cleanParams(params)
  })
  return response.data.data
}

export async function fetchSandboxResources() {
  const response = await http.get<ApiResponse<SandboxResourceSnapshot>>(`${basePath}/resources`)
  return response.data.data
}

export async function reapSandboxIdle() {
  const response = await http.post<ApiResponse<SandboxControlResult>>(`${basePath}/reap-idle`)
  return response.data.data
}

export async function closeSandboxSession(sessionId: string) {
  const response = await http.post<ApiResponse<SandboxControlResult>>(
    `${basePath}/sessions/${encodeURIComponent(sessionId)}/close`
  )
  return response.data.data
}

export async function closeSandboxMissingRuntimeSessions() {
  const response = await http.post<ApiResponse<SandboxControlResult>>(`${basePath}/sessions/close-missing-runtime`)
  return response.data.data
}

export async function killSandboxVm(vmId: string, payload: SandboxControlRequest) {
  const response = await http.post<ApiResponse<SandboxControlResult>>(
    `${basePath}/vms/${encodeURIComponent(vmId)}/kill`,
    payload
  )
  return response.data.data
}

export async function cleanupSandboxOrphans(payload: SandboxControlRequest) {
  const response = await http.post<ApiResponse<SandboxControlResult>>(`${basePath}/resources/cleanup-orphans`, payload)
  return response.data.data
}

export async function clearSandboxRunDir(payload: SandboxControlRequest) {
  const response = await http.post<ApiResponse<SandboxControlResult>>(`${basePath}/run-dir/clear`, payload)
  return response.data.data
}

export async function rebuildSandboxWarmPool(payload: SandboxControlRequest) {
  const response = await http.post<ApiResponse<SandboxControlResult>>(`${basePath}/warm-pool/rebuild`, payload)
  return response.data.data
}

export async function fetchSandboxLogTail(lines = 100) {
  const response = await http.get<ApiResponse<string>>(`${basePath}/logs/tail`, {
    params: { lines }
  })
  return response.data.data
}
