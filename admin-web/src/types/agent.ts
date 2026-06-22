export interface AgentRecord {
  agentId: number
  agentKey: string
  name: string
  shortName?: string | null
  description?: string | null
  systemPrompt: string
  defaultModelId?: number | null
  defaultModelName?: string | null
  enabled: boolean
  sort: number
}

export interface AgentOptionRecord {
  agentId: number
  agentKey: string
  name: string
  shortName?: string | null
  description?: string | null
  defaultModelId?: number | null
  enabled: boolean
  sort: number
}

export interface AgentFormPayload {
  agentKey: string
  name: string
  shortName: string
  description: string
  systemPrompt: string
  defaultModelId: number | null
  enabled: boolean
  sort: number
}

export interface AgentToolBindingRecord {
  bindingId: number
  agentId: number
  toolName: string
  providerType: string
  providerRefId: number
  serverKey?: string | null
  serverName?: string | null
  toolDisplayName?: string | null
  toolDescription?: string | null
  displayName?: string | null
  description?: string | null
  serverEnabled: boolean
  toolEnabled: boolean
  enabled: boolean
  sort?: number | null
  sortOrder?: number | null
}

export interface AgentToolOptionRecord {
  toolName: string
  providerType: string
  providerRefId: number
  serverKey?: string | null
  serverName?: string | null
  remoteToolName?: string | null
  exposedToolName?: string | null
  displayName?: string | null
  description?: string | null
  serverEnabled: boolean
  toolEnabled: boolean
  enabled?: boolean
  sortOrder?: number | null
}

export interface AgentToolBindingPayload {
  toolName: string
  providerType: string
  providerRefId: number
  sort: number
}
