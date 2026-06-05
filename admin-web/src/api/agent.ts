import http from './http'
import type { ApiResponse } from '../types/rbac'
import type { AgentFormPayload, AgentOptionRecord, AgentRecord } from '../types/agent'

export async function fetchAgents() {
  const response = await http.get<ApiResponse<AgentRecord[]>>('/agents')
  return response.data.data
}

export async function fetchAvailableAgents() {
  const response = await http.get<ApiResponse<AgentOptionRecord[]>>('/agents/available')
  return response.data.data
}

export async function createAgent(payload: AgentFormPayload) {
  const response = await http.post<ApiResponse<AgentRecord>>('/agents', payload)
  return response.data.data
}

export async function updateAgent(agentId: number, payload: AgentFormPayload) {
  const response = await http.put<ApiResponse<AgentRecord>>(`/agents/${agentId}`, payload)
  return response.data.data
}

export async function deleteAgent(agentId: number) {
  await http.delete(`/agents/${agentId}`)
}
