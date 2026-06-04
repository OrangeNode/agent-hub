import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { UserProfile } from '../api/auth'
import { USER_PROFILE_KEY, USER_TOKEN_KEY, USERNAME_KEY, clearUserAuthState } from './auth-session'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(USER_TOKEN_KEY) ?? '')
  const username = ref(localStorage.getItem(USERNAME_KEY) ?? '')
  const profile = ref<UserProfile | null>(restoreProfile())

  const isAuthenticated = computed(() => Boolean(token.value))
  const displayName = computed(() => profile.value?.displayName || username.value)

  function login(nextToken: string, nextProfile: UserProfile) {
    username.value = nextProfile.username
    token.value = nextToken
    localStorage.setItem(USER_TOKEN_KEY, nextToken)
    localStorage.setItem(USERNAME_KEY, nextProfile.username)
    localStorage.setItem(USER_PROFILE_KEY, JSON.stringify(nextProfile))
    profile.value = nextProfile
  }

  function logout() {
    username.value = ''
    token.value = ''
    profile.value = null
    clearUserAuthState()
  }

  function restoreProfile() {
    const rawProfile = localStorage.getItem(USER_PROFILE_KEY)
    if (!rawProfile) {
      return null
    }

    try {
      return JSON.parse(rawProfile) as UserProfile
    } catch {
      localStorage.removeItem(USER_PROFILE_KEY)
      return null
    }
  }

  return {
    token,
    username,
    profile,
    isAuthenticated,
    displayName,
    login,
    logout
  }
})
