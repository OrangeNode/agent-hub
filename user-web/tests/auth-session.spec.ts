import { describe, expect, it } from 'vitest'
import { clearUserAuthState, redirectToUserLogin } from '../src/stores/auth-session'

describe('用户端登录态清理', () => {
  it('401 后应该清空本地登录态', () => {
    localStorage.setItem('agent-hub-user-token', 'expired-token')
    localStorage.setItem('agent-hub-user-username', 'demo')
    localStorage.setItem('agent-hub-user-profile', JSON.stringify({ username: 'demo' }))

    clearUserAuthState()

    expect(localStorage.getItem('agent-hub-user-token')).toBeNull()
    expect(localStorage.getItem('agent-hub-user-username')).toBeNull()
    expect(localStorage.getItem('agent-hub-user-profile')).toBeNull()
  })

  it('401 后应该跳转到登录页并携带当前地址', () => {
    window.history.replaceState(null, '', '/chat?conversationId=1')

    redirectToUserLogin()

    expect(window.location.pathname).toBe('/login')
    expect(window.location.search).toBe('?redirect=%2Fchat%3FconversationId%3D1')
  })
})
