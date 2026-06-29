import { describe, expect, it } from 'vitest'
import chatViewSource from '../src/views/ChatView.vue?raw'

const normalizedSource = chatViewSource.replace(/\r\n/g, '\n')

function cssBlock(selector: string) {
  const start = normalizedSource.indexOf(`${selector} {`)
  if (start < 0) {
    return ''
  }
  const end = normalizedSource.indexOf('\n}', start)
  return normalizedSource.slice(start, end)
}

describe('聊天侧栏账号菜单', () => {
  it('使用账号菜单替代底部直接退出按钮', () => {
    expect(normalizedSource).toContain('accountMenuOpen')
    expect(normalizedSource).toContain('account-trigger')
    expect(normalizedSource).toContain('account-plan')
    expect(normalizedSource).toContain('account-footer-icon')
    expect(normalizedSource).toContain('account-menu')
    expect(normalizedSource).toContain('account-menu-header')
    expect(normalizedSource).toContain('account-menu-divider')
    expect(normalizedSource).toContain('退出登录')
    expect(normalizedSource).not.toContain('<strong>{{ authStore.displayName')
    expect(normalizedSource).not.toContain('<n-button quaternary size="small" @click="handleLogout">退出</n-button>')
  })

  it('账号入口默认融入侧栏底色，悬停或打开时才显示灰底', () => {
    expect(cssBlock('.sidebar-footer')).toContain('border-top: 1px solid #e4e4e7;')
    expect(cssBlock('.account-trigger')).toContain('grid-template-columns: 26px minmax(0, 1fr) 22px;')
    expect(cssBlock('.account-trigger')).toContain('min-height: 52px;')
    expect(cssBlock('.account-trigger')).toContain('padding: 8px 10px;')
    expect(cssBlock('.account-trigger')).toContain('background: transparent;')
    expect(normalizedSource).toContain('.account-trigger:hover,\n.account-trigger.open')
    expect(normalizedSource).toContain('background: #ececec;')
    expect(cssBlock('.user-avatar')).toContain('background: #f2a13b;')
    expect(cssBlock('.user-avatar')).toContain('color: #ffffff;')
    expect(cssBlock('.user-name')).toContain('font-weight: 400;')
    expect(cssBlock('.account-plan')).toContain('font-size: 12px;')
    expect(cssBlock('.account-plan')).toContain('color: #8a8a8a;')
  })

  it('账号菜单重置原生按钮样式', () => {
    expect(cssBlock('.account-menu')).toContain('border-radius: 14px;')
    expect(cssBlock('.account-menu')).toContain('bottom: calc(100% + 6px);')
    expect(cssBlock('.account-menu-item')).toContain('border: 0;')
    expect(cssBlock('.account-menu-item')).toContain('background: transparent;')
    expect(cssBlock('.account-menu-item')).toContain('cursor: pointer;')
    expect(cssBlock('.account-menu-item')).toContain('font: inherit;')
  })
})
