import http from './http'
import type { ApiResponse, LoginPayload, LoginResult, UserProfile } from '../types/rbac'

export async function login(payload: LoginPayload) {
  const response = await http.post<ApiResponse<LoginResult>>('/auth/login', payload)
  return response.data.data
}

export async function fetchCurrentUser() {
  const response = await http.get<ApiResponse<UserProfile>>('/auth/me')
  return response.data.data
}
