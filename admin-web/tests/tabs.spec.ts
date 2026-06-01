import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resolveTabTitle } from '../src/stores/tabs.helpers'
import { useTabsStore } from '../src/stores/tabs'

function addSystemTabs() {
  const store = useTabsStore()
  store.addTab({ name: 'system-users', path: '/system/users', meta: { title: '用户管理' } })
  store.addTab({ name: 'system-roles', path: '/system/roles', meta: { title: '角色管理' } })
  store.addTab({ name: 'system-menus', path: '/system/menus', meta: { title: '菜单管理' } })
  return store
}

describe('标签页标题解析', () => {
  it('应该优先使用路由标题', () => {
    const title = resolveTabTitle({
      name: 'system-users',
      path: '/system/users',
      meta: { title: '用户管理' }
    })

    expect(title).toBe('用户管理')
  })

  it('缺少标题时应该回退到路由名称', () => {
    const title = resolveTabTitle({
      name: 'custom-page',
      path: '/custom-page',
      meta: {}
    })

    expect(title).toBe('custom-page')
  })
})

describe('多标签状态管理', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('工作台应始终位于第一位且不可关闭', () => {
    const store = useTabsStore()

    store.addTab({ path: '/dashboard', name: 'dashboard', meta: { title: '工作台' } })
    store.addTab({ path: '/system/users', name: 'system-users', meta: { title: '用户管理' } })

    expect(store.tabs[0].path).toBe('/dashboard')
    expect(store.tabs[0].closable).toBe(false)
    expect(store.tabs.map((item) => item.path)).toEqual(['/dashboard', '/system/users'])
  })

  it('关闭全部后应只保留工作台', () => {
    const store = addSystemTabs()

    const nextPath = store.closeAllTabs()

    expect(nextPath).toBe('/dashboard')
    expect(store.tabs).toHaveLength(1)
    expect(store.tabs[0].path).toBe('/dashboard')
  })

  it('关闭其他应只保留当前标签与工作台', () => {
    const store = addSystemTabs()

    const nextPath = store.closeOtherTabs('/system/roles')

    expect(nextPath).toBe('/system/roles')
    expect(store.tabs.map((item) => item.path)).toEqual(['/dashboard', '/system/roles'])
  })

  it('关闭右侧时应保留当前标签左侧及工作台', () => {
    const store = addSystemTabs()
    store.setActiveTab('/system/menus')

    const nextPath = store.closeTabsToRight('/system/roles')

    expect(nextPath).toBe('/system/roles')
    expect(store.tabs.map((item) => item.path)).toEqual([
      '/dashboard',
      '/system/users',
      '/system/roles'
    ])
  })

  it('拖动排序时不应把工作台移出第一位', () => {
    const store = addSystemTabs()

    store.moveTab('/system/menus', '/system/users')
    store.moveTab('/dashboard', '/system/roles')

    expect(store.tabs.map((item) => item.path)).toEqual([
      '/dashboard',
      '/system/menus',
      '/system/users',
      '/system/roles'
    ])
  })

  it('移除工作台标签时应忽略请求', () => {
    const store = addSystemTabs()

    const nextPath = store.removeTab('/dashboard')

    expect(nextPath).toBe('/dashboard')
    expect(store.tabs[0].path).toBe('/dashboard')
  })
})
