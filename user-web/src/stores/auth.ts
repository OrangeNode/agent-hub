import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

const TOKEN_KEY = 'agent-hub-user-token'
const USERNAME_KEY = 'agent-hub-user-username'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) ?? '')
  const username = ref(localStorage.getItem(USERNAME_KEY) ?? '')

  const isAuthenticated = computed(() => Boolean(token.value))

  function login(nextUsername: string, nextToken: string) {
    username.value = nextUsername
    token.value = nextToken
    localStorage.setItem(TOKEN_KEY, nextToken)
    localStorage.setItem(USERNAME_KEY, nextUsername)
  }

  function logout() {
    username.value = ''
    token.value = ''
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USERNAME_KEY)
  }

  return {
    token,
    username,
    isAuthenticated,
    login,
    logout
  }
})
