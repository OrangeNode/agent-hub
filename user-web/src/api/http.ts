import axios from 'axios'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('agent-hub-user-token')

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})
