import axios from 'axios'
import type { ApiResponse } from '../types/rbac'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 10000
})

http.interceptors.request.use((config) => {
  const token = window.localStorage.getItem('agent-hub-admin-token')

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
      window.localStorage.removeItem('agent-hub-admin-token')
      window.localStorage.removeItem('agent-hub-admin-profile')
    }

    const message = error.response?.data?.message ?? error.message ?? '请求失败'
    return Promise.reject(new Error(message))
  }
)

export default http
