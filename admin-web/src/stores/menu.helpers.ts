import type { MenuRecord, MenuTreeNode, UserProfile } from '../types/rbac'

const sectionTitleMap: Record<string, string> = {
  system: '系统管理',
  ai: 'AI 管理',
  agent: 'Agent 管理',
  sandbox: 'Sandbox 管理'
}

interface SidebarMenuOption {
  key: string
  label: string
  routePath: string
  icon?: string
  children?: SidebarMenuOption[]
}

function toNode(item: MenuRecord): MenuTreeNode {
  return {
    key: item.name,
    menuId: item.menuId,
    label: sectionTitleMap[item.name] ?? item.title,
    routePath: item.path,
    routeName: item.name,
    sort: item.sort,
    icon: item.icon ?? undefined,
    permission: item.permission ?? undefined,
    children: item.children?.length
      ? item.children.map(toNode).sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
      : undefined
  }
}

export function buildMenuTree(records: MenuRecord[]): MenuTreeNode[] {
  if (!records.length) {
    return []
  }

  if (records.some((item) => item.children?.length)) {
    return records
      .map(toNode)
      .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
  }

  const rootMap = new Map<string, MenuTreeNode>()

  for (const item of [...records].sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))) {
    const segments = item.path.split('/').filter(Boolean)

    if (segments.length <= 1) {
      rootMap.set(item.name, {
        key: item.name,
        menuId: item.menuId ?? 0,
        label: item.title,
        routePath: item.path,
        routeName: item.name,
        sort: item.sort ?? 0,
        icon: item.icon ?? undefined,
        permission: item.permission ?? undefined
      })
      continue
    }

    const sectionKey = segments[0]
    const parent = rootMap.get(sectionKey) ?? {
      key: sectionKey,
      menuId: 0,
      label: sectionTitleMap[sectionKey] ?? sectionKey,
      routePath: `/${sectionKey}`,
      routeName: sectionKey,
      sort: item.sort ?? 0,
      children: []
    }

    const nextChildren = [...(parent.children ?? []), {
      key: item.name,
      menuId: item.menuId ?? 0,
      label: item.title,
      routePath: item.path,
      routeName: item.name,
      sort: item.sort ?? 0,
      icon: item.icon ?? undefined,
      permission: item.permission ?? undefined
    }].sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))

    rootMap.set(sectionKey, {
      ...parent,
      children: nextChildren
    })
  }

  return [...rootMap.values()].sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
}

export function buildNaiveMenuOptions(records: MenuTreeNode[]): SidebarMenuOption[] {
  return records.map((item) => ({
    key: item.key,
    label: item.label,
    routePath: item.routePath,
    icon: item.icon,
    children: item.children ? buildNaiveMenuOptions(item.children) : undefined
  }))
}

export function findMenuByPath(records: MenuTreeNode[], path: string): MenuTreeNode | null {
  for (const item of records) {
    if (item.routePath === path) {
      return item
    }

    if (item.children?.length) {
      const matched = findMenuByPath(item.children, path)
      if (matched) {
        return matched
      }
    }
  }

  return null
}

export function filterMenusByProfile(records: MenuTreeNode[], profile: UserProfile | null): MenuTreeNode[] {
  return records
    .map((item) => ({
      ...item,
      children: item.children ? filterMenusByProfile(item.children, profile) : undefined
    }))
    .filter((item) => {
      const granted = !item.permission || !profile || profile.permissions.includes(item.permission)
      const hasChildren = Boolean(item.children?.length)
      return granted && (!['/system', '/ai', '/agent', '/sandbox'].includes(item.routePath) || hasChildren)
    })
}
