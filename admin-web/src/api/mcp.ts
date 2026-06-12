import http from './http'
import type { ApiResponse } from '../types/rbac'
import type {
  McpConnectionTestResult,
  McpServerFormPayload,
  McpServerRecord,
  McpToolCallLogRecord,
  McpToolRecord,
  McpToolSyncResult
} from '../types/mcp'

export async function fetchMcpServers() {
  const response = await http.get<ApiResponse<McpServerRecord[]>>('/mcp/servers')
  return response.data.data
}

export async function createMcpServer(payload: McpServerFormPayload) {
  const response = await http.post<ApiResponse<McpServerRecord>>('/mcp/servers', payload)
  return response.data.data
}

export async function updateMcpServer(serverId: number, payload: Omit<McpServerFormPayload, 'serverKey'>) {
  const response = await http.put<ApiResponse<McpServerRecord>>(`/mcp/servers/${serverId}`, payload)
  return response.data.data
}

export async function deleteMcpServer(serverId: number) {
  await http.delete(`/mcp/servers/${serverId}`)
}

export async function testMcpServer(serverId: number) {
  const response = await http.post<ApiResponse<McpConnectionTestResult>>(`/mcp/servers/${serverId}/test`)
  return response.data.data
}

export async function syncMcpServerTools(serverId: number) {
  const response = await http.post<ApiResponse<McpToolSyncResult>>(`/mcp/servers/${serverId}/sync-tools`)
  return response.data.data
}

export async function fetchMcpTools(serverId: number) {
  const response = await http.get<ApiResponse<McpToolRecord[]>>(`/mcp/servers/${serverId}/tools`)
  return response.data.data
}

export async function updateMcpToolEnabled(toolId: number, enabled: boolean) {
  const response = await http.put<ApiResponse<McpToolRecord>>(`/mcp/tools/${toolId}/enabled`, { enabled })
  return response.data.data
}

export async function fetchMcpCallLogs(params: Record<string, string | number | undefined>) {
  const response = await http.get<ApiResponse<McpToolCallLogRecord[]>>('/mcp/calls', { params })
  return response.data.data
}
