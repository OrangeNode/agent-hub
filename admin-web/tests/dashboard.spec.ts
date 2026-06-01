import { describe, expect, it } from 'vitest'
import { buildDashboardMetrics, buildQuickEntries } from '../src/views/dashboard.helpers'

describe('工作台派生数据', () => {
  it('工作台指标应包含角色数、菜单数、权限点数和活跃标签数', () => {
    const metrics = buildDashboardMetrics(
      {
        userId: 1,
        username: 'admin',
        displayName: '平台管理员',
        enabled: true,
        roles: ['ADMIN'],
        permissions: ['system:user:list', 'system:role:list']
      },
      [
        {
          key: 'dashboard',
          label: '工作台',
          routePath: '/dashboard'
        },
        {
          key: 'system',
          label: '系统管理',
          routePath: '/system',
          children: [
            {
              key: 'system-users',
              label: '用户管理',
              routePath: '/system/users'
            }
          ]
        }
      ],
      [
        { key: 'dashboard', title: '工作台', path: '/dashboard', closable: false },
        { key: 'system-users', title: '用户管理', path: '/system/users', closable: true }
      ]
    )

    expect(metrics.map((item) => item.key)).toEqual([
      'roles',
      'menus',
      'permissions',
      'tabs'
    ])
    expect(metrics.map((item) => item.value)).toEqual([1, 3, 2, 2])
  })

  it('快捷入口应包含 RBAC 三个核心页面', () => {
    expect(buildQuickEntries().map((item) => item.path)).toEqual([
      '/system/users',
      '/system/roles',
      '/system/menus'
    ])
  })
})
