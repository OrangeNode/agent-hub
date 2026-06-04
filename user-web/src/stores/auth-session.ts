export const USER_TOKEN_KEY = 'agent-hub-user-token'
export const USERNAME_KEY = 'agent-hub-user-username'
export const USER_PROFILE_KEY = 'agent-hub-user-profile'

const authCookieNames = [USER_TOKEN_KEY, 'agent-hub-token', 'Authorization']

function removeCookie(name: string) {
  document.cookie = `${encodeURIComponent(name)}=; Max-Age=0; path=/`
}

export function clearUserAuthState() {
  localStorage.removeItem(USER_TOKEN_KEY)
  localStorage.removeItem(USERNAME_KEY)
  localStorage.removeItem(USER_PROFILE_KEY)
  sessionStorage.removeItem(USER_TOKEN_KEY)
  sessionStorage.removeItem(USERNAME_KEY)
  sessionStorage.removeItem(USER_PROFILE_KEY)
  authCookieNames.forEach(removeCookie)
}

export function redirectToUserLogin() {
  if (window.location.pathname === '/login') {
    return
  }

  const redirect = `${window.location.pathname}${window.location.search}${window.location.hash}`
  const target = `/login?redirect=${encodeURIComponent(redirect)}`
  if (import.meta.env.MODE === 'test') {
    window.history.replaceState(null, '', target)
    return
  }

  window.location.assign(target)
}
