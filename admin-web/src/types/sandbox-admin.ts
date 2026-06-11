export type SandboxRiskLevel = 'CRITICAL' | 'WARN' | 'INFO' | string

export type SandboxOperation =
  | 'REAP_IDLE'
  | 'CLOSE_SESSION'
  | 'KILL_VM'
  | 'CLEANUP_ORPHANS'
  | 'CLEAR_RUN_DIR'
  | 'REBUILD_WARM_POOL'

export interface SandboxRisk {
  level: SandboxRiskLevel
  code: string
  title: string
  message: string
  targetType?: string | null
  targetId?: string | null
  recommendedAction?: string | null
}

export interface SandboxOverview {
  serviceHealthy: boolean
  activeSessions: number
  dbOpenSessions: number
  runningExecutions: number
  recentFailedExecutions: number
  firecrackerProcesses: number
  tapDevices: number
  runDirBytes: number
  orphanResources: number
  risks: SandboxRisk[]
}

export interface SandboxRuntimeSession {
  sandboxSessionId: string
  conversationId?: string | null
  vmId?: string | null
  agentUrl?: string | null
  lastActiveAt?: string | null
  activeUses: number
  idleSeconds: number
  idleTimeoutSeconds: number
  runDir?: string | null
  tapName?: string | null
  firecrackerPid?: number | null
  runtimeStatus?: string | null
}

export interface SandboxVmMetadata {
  vmId: string
  agentUrl?: string | null
  runDir?: string | null
  tapName?: string | null
  pid?: number | null
}

export interface SandboxWarmPoolSnapshot {
  targetSize: number
  size: number
  starting: number
  vms: SandboxVmMetadata[]
}

export interface SandboxRunDirInfo {
  vmId?: string | null
  path: string
  sizeBytes: number
  createdAt?: string | null
  hasSocket: boolean
  bound: boolean
  resourceStatus: string
}

export interface SandboxProcessInfo {
  pid: number
  args?: string | null
  apiSocket?: string | null
  vmId?: string | null
  runDir?: string | null
  bound: boolean
  resourceStatus: string
}

export interface SandboxTapInfo {
  name: string
  vmId?: string | null
  bound: boolean
  resourceStatus: string
}

export interface SandboxOrphanResource {
  type: string
  id: string
  path?: string | null
  reason: string
}

export interface SandboxResourceSnapshot {
  scannedAt?: string | null
  runDir?: string | null
  runDirBytes: number
  runDirs: SandboxRunDirInfo[]
  firecrackerProcesses: SandboxProcessInfo[]
  taps: SandboxTapInfo[]
  orphans: SandboxOrphanResource[]
}

export interface SandboxResourceSummary {
  firecrackerProcesses: number
  tapDevices: number
  runDirs: number
  runDirBytes: number
  orphanResources: number
}

export interface SandboxRuntimeStatus {
  serviceHealthy: boolean
  startedAt?: string | null
  uptimeSeconds: number
  activeSessions: number
  sessions: SandboxRuntimeSession[]
  warmPool: SandboxWarmPoolSnapshot
  resources: SandboxResourceSnapshot
  resourceSummary: SandboxResourceSummary
}

export interface SandboxAdminSessionItem {
  id: number
  conversationId?: number | null
  sandboxSessionId: string
  dbStatus?: string | null
  runtimeStatus?: string | null
  vmId?: string | null
  runDir?: string | null
  tapName?: string | null
  firecrackerPid?: number | null
  idleSeconds: number
  lastActiveAt?: string | null
  createdAt?: string | null
  closedAt?: string | null
}

export interface SandboxAdminExecutionItem {
  id: number
  sandboxSessionId?: number | null
  conversationId?: number | null
  toolName?: string | null
  command?: string | null
  workingDir?: string | null
  status?: string | null
  exitCode?: number | null
  errorMessage?: string | null
  durationMs?: number | null
  startedAt?: string | null
  completedAt?: string | null
}

export interface SandboxAdminArtifactItem {
  id: number
  sandboxSessionId?: number | null
  conversationId?: number | null
  executionId?: number | null
  relativePath?: string | null
  fileName?: string | null
  sizeBytes?: number | null
  contentType?: string | null
  version?: number | null
  createdAt?: string | null
}

export interface SandboxSessionQuery {
  status?: string | null
  conversationId?: number | null
  sandboxSessionId?: string | null
}

export interface SandboxExecutionQuery {
  conversationId?: number | null
  sandboxSessionDbId?: number | null
  status?: string | null
}

export interface SandboxArtifactQuery {
  conversationId?: number | null
  sandboxSessionDbId?: number | null
}

export interface SandboxControlRequest {
  reason: string
  confirmText: string
  dryRun: boolean
}

export interface SandboxControlResult {
  success: boolean
  affected: string[]
  errors: string[]
}
