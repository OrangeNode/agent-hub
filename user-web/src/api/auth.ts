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

export function resolveLoginResult(response: ApiResponse<LoginResult | null>) {
  if (response.code !== 200 || !response.data) {
    throw new Error(response.message || '登录失败')
  }

  return response.data
}

export async function login(payload: LoginPayload) {
  const response = await http.post<ApiResponse<LoginResult | null>>('/auth/login', payload)
  return resolveLoginResult(response.data)
}
