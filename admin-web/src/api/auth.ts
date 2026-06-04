import http from './http'
import type { ApiResponse, LoginPayload, LoginResult, UserProfile } from '../types/rbac'

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

export async function fetchCurrentUser() {
  const response = await http.get<ApiResponse<UserProfile>>('/auth/me')
  return response.data.data
}
