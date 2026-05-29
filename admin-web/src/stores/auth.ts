import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchCurrentUser, login } from '../api/auth'
import type { LoginPayload, UserProfile } from '../types/rbac'

const tokenKey = 'agent-hub-admin-token'
const profileKey = 'agent-hub-admin-profile'

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
      token.value = response.token
      profile.value = response.profile
      persist()
    } finally {
      loading.value = false
    }
  }

  async function refreshProfile() {
    if (!token.value) {
      return null
    }

    profile.value = await fetchCurrentUser()
    persist()
    return profile.value
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
