export interface AiProviderRecord {
  providerId: number
  code: string
  name: string
  providerType: string
  protocolType: string
  baseUrl: string
  authType: string
  apiVersion?: string | null
  extraHeaders?: string | null
  extraBody?: string | null
  hasApiKey: boolean
  enabled: boolean
  sort: number
}

export interface AiProviderFormPayload {
  code: string
  name: string
  providerType: string
  protocolType: string
  baseUrl: string
  apiKey: string
  authType: string
  apiVersion: string
  extraHeaders: string
  extraBody: string
  enabled: boolean
  sort: number
}

export interface AiProviderConnectionTestResult {
  success: boolean
  message: string
  statusCode?: number | null
}

export interface AiModelRecord {
  modelId: number
  providerId: number
  providerCode: string
  providerName: string
  modelCode: string
  displayName: string
  capabilityType: string
  streamSupported: boolean
  reasoningSupported: boolean
  imageInputSupported: boolean
  imageTokenEstimate?: number | null
  contextWindow?: number | null
  maxOutputTokens?: number | null
  defaultModel: boolean
  enabled: boolean
  sort: number
}

export interface AiModelFormPayload {
  providerId: number | null
  modelCode: string
  displayName: string
  capabilityType: string
  streamSupported: boolean
  reasoningSupported: boolean
  imageInputSupported: boolean
  imageTokenEstimate?: number | null
  contextWindow: number | null
  maxOutputTokens: number | null
  defaultModel: boolean
  enabled: boolean
  sort: number
}
