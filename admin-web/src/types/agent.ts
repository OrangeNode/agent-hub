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
