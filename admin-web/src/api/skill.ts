import http from './http'
import type { ApiResponse } from '../types/rbac'
import type {
  AgentSkillBindingRecord,
  SkillFileContent,
  SkillFileRecord,
  SkillImportPreview,
  SkillOptionRecord,
  SkillRecord,
  SkillUploadUrl
} from '../types/skill'

export async function fetchSkills() {
  const response = await http.get<ApiResponse<SkillRecord[]>>('/skills')
  return response.data.data
}

export async function fetchAvailableSkills() {
  const response = await http.get<ApiResponse<SkillOptionRecord[]>>('/skills/available')
  return response.data.data
}

export async function createSkillUploadUrl(file: File) {
  const response = await http.post<ApiResponse<SkillUploadUrl>>('/skills/upload-url', {
    filename: file.name,
    contentType: file.type || 'application/zip',
    fileSize: file.size
  })
  return response.data.data
}

export async function confirmSkillImport(importId: number) {
  const response = await http.post<ApiResponse<SkillImportPreview>>(`/skills/imports/${importId}/confirm`)
  return response.data.data
}

export async function applySkillImport(importId: number, mode: 'OVERWRITE' | 'RENAME', name?: string) {
  const response = await http.post<ApiResponse<SkillRecord>>(`/skills/imports/${importId}/apply`, {
    mode,
    name
  })
  return response.data.data
}

export async function uploadSkillArchive(file: File) {
  const upload = await createSkillUploadUrl(file)
  const response = await fetch(upload.uploadUrl, {
    method: upload.method,
    headers: upload.headers,
    body: file
  })

  if (!response.ok) {
    throw new Error('Skill 包上传失败')
  }

  return confirmSkillImport(upload.importId)
}

export async function fetchSkillFiles(skillId: number) {
  const response = await http.get<ApiResponse<SkillFileRecord[]>>(`/skills/${skillId}/files`)
  return response.data.data
}

export async function fetchSkillFileContent(skillId: number, path: string) {
  const response = await http.get<ApiResponse<SkillFileContent>>(`/skills/${skillId}/files/content`, {
    params: { path }
  })
  return response.data.data
}

export async function deleteSkill(skillId: number) {
  await http.delete(`/skills/${skillId}`)
}

export async function fetchAgentSkillBindings(agentId: number) {
  const response = await http.get<ApiResponse<AgentSkillBindingRecord[]>>(`/agents/${agentId}/skills`)
  return response.data.data
}

export async function updateAgentSkillBindings(agentId: number, skillIds: number[]) {
  const response = await http.put<ApiResponse<AgentSkillBindingRecord[]>>(`/agents/${agentId}/skills`, {
    skills: skillIds.map((skillId, index) => ({ skillId, sort: index }))
  })
  return response.data.data
}
