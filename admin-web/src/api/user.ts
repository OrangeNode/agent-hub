import http from './http'
import type { ApiResponse, UserFormPayload, UserProfile, UserRecord } from '../types/rbac'

function mapUserProfile(profile: UserProfile, index: number): UserRecord {
  return {
    userId: profile.userId,
    username: profile.username,
    displayName: profile.displayName,
    roles: profile.roles,
    enabled: profile.enabled,
    permissions: profile.permissions
  }
}

export async function fetchUsers() {
  const response = await http.get<ApiResponse<UserProfile[]>>('/system/users')
  return response.data.data.map(mapUserProfile)
}

export async function createUser(payload: UserFormPayload) {
  const response = await http.post<ApiResponse<UserProfile>>('/system/users', payload)
  return mapUserProfile(response.data.data, 0)
}

export async function updateUser(userId: number, payload: Omit<UserFormPayload, 'username' | 'password'>) {
  const response = await http.put<ApiResponse<UserProfile>>(`/system/users/${userId}`, payload)
  return mapUserProfile(response.data.data, 0)
}

export async function updateUserStatus(userId: number, enabled: boolean) {
  const response = await http.patch<ApiResponse<UserProfile>>(`/system/users/${userId}/status`, null, {
    params: { enabled }
  })
  return mapUserProfile(response.data.data, 0)
}

export async function deleteUser(userId: number) {
  await http.delete(`/system/users/${userId}`)
}
