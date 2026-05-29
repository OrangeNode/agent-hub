import { describe, expect, it } from 'vitest'
import { buildMenuTree, buildNaiveMenuOptions } from '../src/stores/menu.helpers'

describe('动态菜单转换', () => {
  it('应该把后端扁平菜单转换成层级菜单树', () => {
    const tree = buildMenuTree([
      { path: '/dashboard', name: 'dashboard', title: '工作台' },
      { path: '/system/users', name: 'system-users', title: '用户管理' },
      { path: '/system/roles', name: 'system-roles', title: '角色管理' },
      { path: '/system/menus', name: 'system-menus', title: '菜单管理' }
    ])

    expect(tree).toHaveLength(2)
    expect(tree[1].children?.map((item) => item.key)).toEqual([
      'system-users',
      'system-roles',
      'system-menus'
    ])
  })

  it('应该生成可供侧边栏直接使用的菜单项', () => {
    const options = buildNaiveMenuOptions([
      {
        key: 'system',
        label: '系统管理',
        routePath: '/system',
        children: [{ key: 'system-users', label: '用户管理', routePath: '/system/users' }]
      }
    ])

    expect(options[0].label).toBe('系统管理')
    expect(options[0].children?.[0].key).toBe('system-users')
  })
})
