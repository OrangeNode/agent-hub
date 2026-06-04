export const ADMIN_TOKEN_KEY = 'agent-hub-admin-token'
export const ADMIN_PROFILE_KEY = 'agent-hub-admin-profile'

const authCookieNames = [ADMIN_TOKEN_KEY, 'agent-hub-token', 'Authorization']

function removeCookie(name: string) {
  document.cookie = `${encodeURIComponent(name)}=; Max-Age=0; path=/`
}

export function clearAdminAuthState() {
  window.localStorage.removeItem(ADMIN_TOKEN_KEY)
  window.localStorage.removeItem(ADMIN_PROFILE_KEY)
  window.sessionStorage.removeItem(ADMIN_TOKEN_KEY)
  window.sessionStorage.removeItem(ADMIN_PROFILE_KEY)
  authCookieNames.forEach(removeCookie)
}

export function redirectToAdminLogin() {
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
