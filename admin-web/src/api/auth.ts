import http from './http'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface LoginPayload {
  username: string
  password: string
}

export interface UserProfile {
  userId: number
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

export interface LoginResult {
  token: string
  profile: UserProfile
}

export async function login(payload: LoginPayload) {
  const response = await http.post<ApiResponse<LoginResult>>('/auth/login', payload)
  return response.data
}

export async function fetchCurrentUser() {
  const response = await http.get<ApiResponse<UserProfile>>('/auth/me')
  return response.data
}
