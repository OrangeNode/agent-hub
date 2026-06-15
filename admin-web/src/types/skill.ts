export interface SkillRecord {
  skillId: number
  skillKey: string
  name: string
  description?: string | null
  originalFilename?: string | null
  skillMdContent?: string | null
  fileCount: number
  updatedAt?: string | null
}

export interface SkillOptionRecord {
  skillId: number
  skillKey: string
  name: string
  description?: string | null
}

export interface SkillUploadUrl {
  importId: number
  uploadUrl: string
  method: string
  headers: Record<string, string>
  expiresAt: string
}

export interface SkillImportPreview {
  importId: number
  duplicate: boolean
  imported: boolean
  duplicateSkillId?: number | null
  parsedName: string
  parsedDescription?: string | null
  fileCount: number
  skillMdContent: string
  importedSkill?: SkillRecord | null
}

export interface SkillFileRecord {
  fileId: number
  skillId: number
  relativePath: string
  directory: boolean
  sizeBytes: number
  sha256?: string | null
}

export interface SkillFileContent {
  fileId: number
  skillId: number
  relativePath: string
  sizeBytes: number
  previewable: boolean
  content?: string | null
  message?: string | null
}

export interface AgentSkillBindingRecord {
  bindingId: number
  agentId: number
  skillId: number
  skillKey?: string | null
  skillName?: string | null
  sort: number
}
