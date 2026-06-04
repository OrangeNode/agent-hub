import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchCurrentUser, login } from '../api/auth'
import type { LoginPayload, UserProfile } from '../types/rbac'
import { ADMIN_PROFILE_KEY, ADMIN_TOKEN_KEY, clearAdminAuthState } from './auth-session'

export const useAuthStore = defineStore('auth', () => {
  const token = ref('')
  const profile = ref<UserProfile | null>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => Boolean(token.value))

  function persist() {
    if (token.value) {
      window.localStorage.setItem(ADMIN_TOKEN_KEY, token.value)
    } else {
      window.localStorage.removeItem(ADMIN_TOKEN_KEY)
    }

    if (profile.value) {
      window.localStorage.setItem(ADMIN_PROFILE_KEY, JSON.stringify(profile.value))
    } else {
      window.localStorage.removeItem(ADMIN_PROFILE_KEY)
    }
  }

  function restore() {
    token.value = window.localStorage.getItem(ADMIN_TOKEN_KEY) ?? ''

    const rawProfile = window.localStorage.getItem(ADMIN_PROFILE_KEY)
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
    clearAdminAuthState()
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
