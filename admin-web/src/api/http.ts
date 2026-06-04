import axios from 'axios'
import type { ApiResponse } from '../types/rbac'
import { ADMIN_TOKEN_KEY, clearAdminAuthState, redirectToAdminLogin } from '../stores/auth-session'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 10000
})

http.interceptors.request.use((config) => {
  const token = window.localStorage.getItem(ADMIN_TOKEN_KEY)

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

http.interceptors.response.use(
  (response) => {
    const payload = response.data as ApiResponse<unknown> | undefined

    if (payload && typeof payload.code === 'number' && payload.code !== 200) {
      return Promise.reject(new Error(payload.message || '请求失败'))
    }

    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      clearAdminAuthState()
      redirectToAdminLogin()
    }

    const message = error.response?.data?.message ?? error.message ?? '请求失败'
    return Promise.reject(new Error(message))
  }
)

export default http
