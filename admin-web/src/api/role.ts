import http from './http'
import type { ApiResponse, RoleFormPayload, RoleRecord } from '../types/rbac'

interface BackendRoleRecord {
  roleId: number
  code: string
  name: string
  enabled: boolean
  menuIds: number[]
}

function mapRole(role: BackendRoleRecord): RoleRecord {
  return {
    roleId: role.roleId,
    code: role.code,
    name: role.name,
    enabled: role.enabled,
    menuIds: role.menuIds
  }
}

export async function fetchRoles() {
  const response = await http.get<ApiResponse<BackendRoleRecord[]>>('/system/roles')
  return response.data.data.map(mapRole)
}

export async function createRole(payload: RoleFormPayload) {
  const response = await http.post<ApiResponse<BackendRoleRecord>>('/system/roles', payload)
  return mapRole(response.data.data)
}

export async function updateRole(roleId: number, payload: Omit<RoleFormPayload, 'code'>) {
  const response = await http.put<ApiResponse<BackendRoleRecord>>(`/system/roles/${roleId}`, payload)
  return mapRole(response.data.data)
}

export async function deleteRole(roleId: number) {
  await http.delete(`/system/roles/${roleId}`)
}
