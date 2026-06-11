import { describe, expect, it } from 'vitest'
import router from '../src/router'

describe('管理端路由', () => {
  it('应该提供独立的工作台路由', () => {
    const dashboardRoute = router.getRoutes().find((route) => route.path === '/dashboard')

    expect(dashboardRoute, '缺少 /dashboard 路由').toBeDefined()
    expect(dashboardRoute?.meta.title).toBe('工作台')
  })

  it('应该保留 RBAC 三个核心页面路由', () => {
    const routeNames = router.getRoutes().map((route) => String(route.name ?? ''))

    expect(routeNames).toContain('system-users')
    expect(routeNames).toContain('system-roles')
    expect(routeNames).toContain('system-menus')
  })

  it('应该提供 Sandbox 运维页面路由', () => {
    const routeNames = router.getRoutes().map((route) => String(route.name ?? ''))

    expect(routeNames).toContain('sandbox-monitor')
  })
})
