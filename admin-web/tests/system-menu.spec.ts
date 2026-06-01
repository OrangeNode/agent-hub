import { describe, expect, it } from 'vitest'
import { buildMenuParentOptions, buildMenuTableData } from '../src/views/system-menu.helpers'

describe('菜单管理树表数据', () => {
  it('应该保留菜单层级而不是直接拍平全部子节点', () => {
    const rows = buildMenuTableData([
      {
        menuId: 2,
        path: '/system',
        name: 'system',
        title: '系统管理',
        sort: 20,
        children: [
          {
            menuId: 21,
            parentId: 2,
            path: '/system/users',
            name: 'system-users',
            title: '用户管理',
            sort: 10
          }
        ]
      },
      {
        menuId: 1,
        path: '/dashboard',
        name: 'dashboard',
        title: '工作台',
        sort: 10
      }
    ])

    expect(rows).toHaveLength(2)
    expect(rows[0].name).toBe('dashboard')
    expect(rows[1].children).toHaveLength(1)
    expect(rows[1].children?.[0].name).toBe('system-users')
  })

  it('应该给上级菜单选择器保留树形选项', () => {
    const options = buildMenuParentOptions([
      {
        menuId: 2,
        path: '/system',
        name: 'system',
        title: '系统管理',
        children: [
          {
            menuId: 21,
            parentId: 2,
            path: '/system/users',
            name: 'system-users',
            title: '用户管理'
          }
        ]
      }
    ])

    expect(options[0].label).toBe('系统管理')
    expect(options[0].children?.[0].label).toBe('用户管理')
  })
})
