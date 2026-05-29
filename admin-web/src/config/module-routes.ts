import type { RouteRecordRaw } from 'vue-router'
import ModuleTableView from '../views/ModuleTableView.vue'

export interface ModuleRow {
  name: string
  code: string
  owner: string
  status: string
  updatedAt: string
}

export interface ModuleDefinition {
  key: string
  path: string
  title: string
  section: string
  description: string
  rows: ModuleRow[]
}

export const moduleDefinitions: ModuleDefinition[] = [
  {
    key: 'system-users',
    path: 'system/users',
    title: '用户管理',
    section: '系统管理',
    description: '查看平台账号、归属角色和最近活跃时间。',
    rows: [
      { name: '管理员', code: 'admin', owner: '平台', status: '启用', updatedAt: '2026-05-29 09:30' },
      { name: '运营专员', code: 'operator', owner: '系统模块', status: '启用', updatedAt: '2026-05-29 10:05' }
    ]
  },
  {
    key: 'system-roles',
    path: 'system/roles',
    title: '角色管理',
    section: '系统管理',
    description: '维护平台角色分组和权限归属。',
    rows: [
      { name: '超级管理员', code: 'ADMIN', owner: '权限中心', status: '启用', updatedAt: '2026-05-29 09:20' },
      { name: '业务运营', code: 'OPERATOR', owner: '权限中心', status: '启用', updatedAt: '2026-05-29 09:45' }
    ]
  },
  {
    key: 'system-permissions',
    path: 'system/permissions',
    title: '权限管理',
    section: '系统管理',
    description: '展示权限编码、资源归属和分配状态。',
    rows: [
      { name: '查看用户', code: 'system:user:list', owner: '系统管理', status: '启用', updatedAt: '2026-05-29 08:50' },
      { name: '管理 Agent', code: 'agent:manage', owner: '智能体中心', status: '启用', updatedAt: '2026-05-29 09:55' }
    ]
  },
  {
    key: 'system-menus',
    path: 'system/menus',
    title: '菜单管理',
    section: '系统管理',
    description: '用于查看后台导航和可见性配置。',
    rows: [
      { name: '仪表盘', code: 'dashboard', owner: '管理端', status: '启用', updatedAt: '2026-05-29 08:30' },
      { name: '模型管理', code: 'ai-models', owner: 'AI 配置', status: '启用', updatedAt: '2026-05-29 09:10' }
    ]
  },
  {
    key: 'ai-providers',
    path: 'ai/providers',
    title: '供应商管理',
    section: 'AI 配置',
    description: '查看大模型供应商接入状态和默认路由。',
    rows: [
      { name: 'OpenAI', code: 'openai', owner: 'AI 平台', status: '待配置', updatedAt: '2026-05-29 11:00' },
      { name: 'Azure OpenAI', code: 'azure-openai', owner: 'AI 平台', status: '待配置', updatedAt: '2026-05-29 11:10' }
    ]
  },
  {
    key: 'ai-models',
    path: 'ai/models',
    title: '模型管理',
    section: 'AI 配置',
    description: '用于维护模型清单、上下文大小和启用策略。',
    rows: [
      { name: 'GPT-4.1', code: 'gpt-4.1', owner: 'OpenAI', status: '待配置', updatedAt: '2026-05-29 11:05' },
      { name: 'GPT-4o-mini', code: 'gpt-4o-mini', owner: 'OpenAI', status: '待配置', updatedAt: '2026-05-29 11:15' }
    ]
  },
  {
    key: 'mcp-servers',
    path: 'mcp/servers',
    title: 'MCP 服务',
    section: '扩展能力',
    description: '查看 MCP Server 的接入点、健康状态与所属环境。',
    rows: [
      { name: '本地文件服务', code: 'local-fs', owner: '开发环境', status: '启用', updatedAt: '2026-05-29 10:15' },
      { name: '浏览器服务', code: 'browser', owner: '开发环境', status: '启用', updatedAt: '2026-05-29 10:18' }
    ]
  },
  {
    key: 'skills',
    path: 'skills',
    title: 'Skill 库',
    section: '扩展能力',
    description: '管理技能模板、作用域和启用状态。',
    rows: [
      { name: '调试工作流', code: 'systematic-debugging', owner: 'Superpowers', status: '启用', updatedAt: '2026-05-29 10:25' },
      { name: '测试驱动开发', code: 'test-driven-development', owner: 'Superpowers', status: '启用', updatedAt: '2026-05-29 10:28' }
    ]
  },
  {
    key: 'agents',
    path: 'agents',
    title: 'Agent 管理',
    section: '智能体中心',
    description: '查看 Agent 配置、可用模型和最近运行概况。',
    rows: [
      { name: '客服助理', code: 'support-agent', owner: '运营团队', status: '草稿', updatedAt: '2026-05-29 10:40' },
      { name: '知识检索助手', code: 'knowledge-agent', owner: '产品团队', status: '启用', updatedAt: '2026-05-29 10:55' }
    ]
  }
]

export const moduleRouteGroups: RouteRecordRaw[] = moduleDefinitions.map((item) => ({
  path: item.path,
  name: item.key,
  component: ModuleTableView,
  meta: {
    title: item.title,
    section: item.section,
    description: item.description,
    requiresAuth: true
  }
}))

export const navigationGroups = Array.from(
  moduleDefinitions.reduce((groups, item) => {
    const current = groups.get(item.section) ?? []
    current.push(item)
    groups.set(item.section, current)
    return groups
  }, new Map<string, ModuleDefinition[]>())
)

export function getModuleDefinition(routeName?: string | symbol | null) {
  return moduleDefinitions.find((item) => item.key === routeName)
}
