import axios from 'axios'

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

export default http
