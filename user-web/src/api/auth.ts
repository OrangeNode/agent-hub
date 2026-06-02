import type { ApiResponse } from './http'
import { http } from './http'

export interface LoginPayload {
  username: string
  password: string
}

export interface UserProfile {
  userId: number
  username: string
  displayName: string
  enabled: boolean
  roles: string[]
  permissions: string[]
}

export interface LoginResult {
  token: string
  profile: UserProfile
}

export async function login(payload: LoginPayload) {
  const response = await http.post<ApiResponse<LoginResult>>('/auth/login', payload)
  return response.data.data
}
