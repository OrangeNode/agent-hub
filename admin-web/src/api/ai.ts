import http from './http'
import type { ApiResponse } from '../types/rbac'
import type {
  AiProviderConnectionTestResult,
  AiModelFormPayload,
  AiModelRecord,
  AiProviderFormPayload,
  AiProviderRecord
} from '../types/ai'

export async function fetchAiProviders() {
  const response = await http.get<ApiResponse<AiProviderRecord[]>>('/ai/providers')
  return response.data.data
}

export async function createAiProvider(payload: AiProviderFormPayload) {
  const response = await http.post<ApiResponse<AiProviderRecord>>('/ai/providers', payload)
  return response.data.data
}

export async function updateAiProvider(
  providerId: number,
  payload: Omit<AiProviderFormPayload, 'code'>
) {
  const response = await http.put<ApiResponse<AiProviderRecord>>(`/ai/providers/${providerId}`, payload)
  return response.data.data
}

export async function deleteAiProvider(providerId: number) {
  await http.delete(`/ai/providers/${providerId}`)
}

export async function testAiProviderConnection(providerId: number) {
  const response = await http.post<ApiResponse<AiProviderConnectionTestResult>>(
    `/ai/providers/${providerId}/test-connection`
  )
  return response.data.data
}

export async function fetchAiModels() {
  const response = await http.get<ApiResponse<AiModelRecord[]>>('/ai/models')
  return response.data.data
}

export async function createAiModel(payload: AiModelFormPayload) {
  const response = await http.post<ApiResponse<AiModelRecord>>('/ai/models', payload)
  return response.data.data
}

export async function updateAiModel(modelId: number, payload: AiModelFormPayload) {
  const response = await http.put<ApiResponse<AiModelRecord>>(`/ai/models/${modelId}`, payload)
  return response.data.data
}

export async function deleteAiModel(modelId: number) {
  await http.delete(`/ai/models/${modelId}`)
}
