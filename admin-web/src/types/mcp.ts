export interface McpServerRecord {
  serverId: number
  serverKey: string
  name: string
  description?: string | null
  endpointUrl: string
  transportType: string
  protocolVersion: string
  authType: string
  hasAuthToken: boolean
  extraHeaders?: string | null
  connectTimeoutSeconds: number
  requestTimeoutSeconds: number
  enabled: boolean
  lastSyncStatus?: string | null
  lastSyncMessage?: string | null
  lastSyncedAt?: string | null
  toolCount: number
  sort: number
}

export interface McpServerFormPayload {
  serverKey?: string
  name: string
  description?: string
  endpointUrl: string
  transportType: string
  protocolVersion: string
  authType: string
  authToken?: string
  extraHeaders?: string
  connectTimeoutSeconds: number
  requestTimeoutSeconds: number
  enabled: boolean
  sort: number
}

export interface McpToolRecord {
  toolId: number
  serverId: number
  serverKey: string
  toolName: string
  exposedToolName: string
  displayName: string
  description?: string | null
  inputSchema: string
  enabled: boolean
  lastSeenAt: string
}

export interface McpConnectionTestResult {
  success: boolean
  protocolVersion?: string | null
  serverName?: string | null
  message: string
}

export interface McpToolSyncResult {
  success: boolean
  syncedCount: number
  message: string
}

export interface McpToolCallLogRecord {
  callId: number
  serverKey: string
  exposedToolName: string
  remoteToolName: string
  conversationId?: number | null
  userId?: number | null
  status: string
  errorMessage?: string | null
  durationMs?: number | null
  startedAt: string
}
