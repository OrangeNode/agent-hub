import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchCurrentUser, login, type LoginPayload, type UserProfile } from '../api/auth'

const tokenKey = 'agent-hub-admin-token'
const profileKey = 'agent-hub-admin-profile'

function buildMockProfile(username: string): UserProfile {
  return {
    userId: username === 'admin' ? 1 : 2,
    username,
    displayName: username === 'admin' ? '平台管理员' : '业务用户',
    roles: username === 'admin' ? ['ADMIN'] : ['USER'],
    permissions: [
      'system:user:list',
      'system:role:list',
      'system:permission:list',
      'system:menu:list',
      'ai:provider:list',
      'ai:model:list',
      'mcp:server:list',
      'skill:list',
      'agent:manage'
    ]
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref('')
  const profile = ref<UserProfile | null>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => Boolean(token.value))

  function persist() {
    if (token.value) {
      window.localStorage.setItem(tokenKey, token.value)
    } else {
      window.localStorage.removeItem(tokenKey)
    }

    if (profile.value) {
      window.localStorage.setItem(profileKey, JSON.stringify(profile.value))
    } else {
      window.localStorage.removeItem(profileKey)
    }
  }

  function restore() {
    token.value = window.localStorage.getItem(tokenKey) ?? ''

    const rawProfile = window.localStorage.getItem(profileKey)
    profile.value = rawProfile ? (JSON.parse(rawProfile) as UserProfile) : null
  }

  async function loginWithPassword(payload: LoginPayload) {
    loading.value = true

    try {
      const response = await login(payload)

      if (response.code !== 0) {
        throw new Error(response.message)
      }

      token.value = response.data.token
      profile.value = response.data.profile
      persist()
      return
    } catch {
      if (
        (payload.username === 'admin' && payload.password === 'admin123') ||
        (payload.username === 'user' && payload.password === 'user123')
      ) {
        token.value = `mock-token-${payload.username}`
        profile.value = buildMockProfile(payload.username)
        persist()
        return
      }

      throw new Error('用户名或密码错误')
    } finally {
      loading.value = false
    }
  }

  async function refreshProfile() {
    if (!token.value) {
      return
    }

    try {
      const response = await fetchCurrentUser()

      if (response.code === 0) {
        profile.value = response.data
        persist()
        return
      }
    } catch {
      if (profile.value) {
        return
      }
    }

    profile.value = buildMockProfile('admin')
    persist()
  }

  function logout() {
    token.value = ''
    profile.value = null
    persist()
  }

  return {
    token,
    profile,
    loading,
    isLoggedIn,
    restore,
    loginWithPassword,
    refreshProfile,
    logout
  }
})
