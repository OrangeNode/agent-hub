import type { MenuTreeNode, TabItem, UserProfile } from '../types/rbac'

export interface DashboardMetric {
  key: string
  label: string
  value: number
  tip: string
}

export interface DashboardEntry {
  title: string
  description: string
  path: string
}

export function countMenuNodes(records: MenuTreeNode[]): number {
  return records.reduce((total, item) => total + 1 + countMenuNodes(item.children ?? []), 0)
}

export function buildDashboardMetrics(
  profile: UserProfile | null,
  menus: MenuTreeNode[],
  tabs: TabItem[]
): DashboardMetric[] {
  return [
    {
      key: 'roles',
      label: '当前角色',
      value: profile?.roles.length ?? 0,
      tip: '已分配角色数量'
    },
    {
      key: 'menus',
      label: '可见菜单',
      value: countMenuNodes(menus),
      tip: '当前账号可访问导航'
    },
    {
      key: 'permissions',
      label: '权限点',
      value: profile?.permissions.length ?? 0,
      tip: '当前账号拥有的权限'
    },
    {
      key: 'tabs',
      label: '活跃标签',
      value: tabs.length,
      tip: '当前工作区已打开页面'
    }
  ]
}

export function buildQuickEntries(): DashboardEntry[] {
  return [
    {
      title: '用户管理',
      description: '维护后台账号、状态和角色归属',
      path: '/system/users'
    },
    {
      title: '角色管理',
      description: '配置角色编码、菜单授权和启停状态',
      path: '/system/roles'
    },
    {
      title: '菜单管理',
      description: '维护系统导航结构、显示和路由元数据',
      path: '/system/menus'
    }
  ]
}
