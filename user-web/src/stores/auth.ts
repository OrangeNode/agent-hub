import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { UserProfile } from '../api/auth'

const TOKEN_KEY = 'agent-hub-user-token'
const USERNAME_KEY = 'agent-hub-user-username'
const PROFILE_KEY = 'agent-hub-user-profile'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) ?? '')
  const username = ref(localStorage.getItem(USERNAME_KEY) ?? '')
  const profile = ref<UserProfile | null>(restoreProfile())

  const isAuthenticated = computed(() => Boolean(token.value))
  const displayName = computed(() => profile.value?.displayName || username.value)

  function login(nextToken: string, nextProfile: UserProfile) {
    username.value = nextProfile.username
    token.value = nextToken
    localStorage.setItem(TOKEN_KEY, nextToken)
    localStorage.setItem(USERNAME_KEY, nextProfile.username)
    localStorage.setItem(PROFILE_KEY, JSON.stringify(nextProfile))
    profile.value = nextProfile
  }

  function logout() {
    username.value = ''
    token.value = ''
    profile.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USERNAME_KEY)
    localStorage.removeItem(PROFILE_KEY)
  }

  function restoreProfile() {
    const rawProfile = localStorage.getItem(PROFILE_KEY)
    if (!rawProfile) {
      return null
    }

    try {
      return JSON.parse(rawProfile) as UserProfile
    } catch {
      localStorage.removeItem(PROFILE_KEY)
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
