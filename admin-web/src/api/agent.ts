import http from './http'
import type { ApiResponse } from '../types/rbac'
import type {
  AgentFormPayload,
  AgentOptionRecord,
  AgentRecord,
  AgentToolBindingPayload,
  AgentToolBindingRecord,
  AgentToolOptionRecord
} from '../types/agent'

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

export async function fetchAgentToolBindings(agentId: number) {
  const response = await http.get<ApiResponse<AgentToolBindingRecord[]>>(`/agents/${agentId}/tools`)
  return response.data.data
}

export async function fetchAgentToolOptions(agentId: number) {
  const response = await http.get<ApiResponse<AgentToolOptionRecord[]>>(`/agents/${agentId}/tools/options`)
  return response.data.data
}

export async function updateAgentToolBindings(agentId: number, tools: AgentToolBindingPayload[]) {
  const response = await http.put<ApiResponse<AgentToolBindingRecord[]>>(`/agents/${agentId}/tools`, {
    tools: tools.map((item) => ({
      toolName: item.toolName,
      providerType: item.providerType,
      providerRefId: item.providerRefId,
      enabled: item.enabled,
      sortOrder: item.sortOrder,
      sort: item.sortOrder
    }))
  })
  return response.data.data
}
