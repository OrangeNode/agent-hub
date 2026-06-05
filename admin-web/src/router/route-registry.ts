import type { Component } from 'vue'

export interface RouteMenuDefinition {
  path: string
  name: string
  title: string
  icon?: string
  permission?: string
  component: () => Promise<Component>
}

export const routeMenuDefinitions: RouteMenuDefinition[] = [
  {
    path: '/dashboard',
    name: 'dashboard',
    title: '工作台',
    icon: 'grid',
    component: () => import('../views/DashboardView.vue')
  },
  {
    path: '/system/users',
    name: 'system-users',
    title: '用户管理',
    icon: 'people',
    permission: 'system:user:list',
    component: () => import('../views/SystemUsersView.vue')
  },
  {
    path: '/system/roles',
    name: 'system-roles',
    title: '角色管理',
    icon: 'shield',
    permission: 'system:role:list',
    component: () => import('../views/SystemRolesView.vue')
  },
  {
    path: '/system/menus',
    name: 'system-menus',
    title: '菜单管理',
    icon: 'menu',
    permission: 'system:menu:list',
    component: () => import('../views/SystemMenusView.vue')
  },
  {
    path: '/ai/providers',
    name: 'ai-providers',
    title: '供应商管理',
    icon: 'api',
    permission: 'ai:provider:list',
    component: () => import('../views/AiProvidersView.vue')
  },
  {
    path: '/ai/models',
    name: 'ai-models',
    title: '模型管理',
    icon: 'partition',
    permission: 'ai:model:list',
    component: () => import('../views/AiModelsView.vue')
  },
  {
    path: '/agent/list',
    name: 'agent-list',
    title: 'Agent 列表',
    icon: 'robot',
    permission: 'agent:list',
    component: () => import('../views/AgentsView.vue')
  }
]
