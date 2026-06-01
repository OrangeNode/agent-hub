import type { TabItem, TabRouteLike } from '../types/rbac'

export const DASHBOARD_TAB: TabItem = {
  key: 'dashboard',
  title: '工作台',
  path: '/dashboard',
  closable: false
}

export function resolveTabTitle(route: TabRouteLike) {
  const title = route.meta?.title

  if (typeof title === 'string' && title.trim()) {
    return title
  }

  if (typeof route.name === 'string' && route.name.trim()) {
    return route.name
  }

  return route.path
}

export function ensureDashboardTab(tabs: TabItem[]) {
  const dashboard = tabs.find((item) => item.path === DASHBOARD_TAB.path) ?? { ...DASHBOARD_TAB }
  const rest = tabs.filter((item) => item.path !== DASHBOARD_TAB.path)
  return [dashboard, ...rest]
}

export function moveTabWithinClosableList(tabs: TabItem[], fromPath: string, toPath: string) {
  if (fromPath === DASHBOARD_TAB.path || toPath === DASHBOARD_TAB.path || fromPath === toPath) {
    return ensureDashboardTab(tabs)
  }

  const nextTabs = [...ensureDashboardTab(tabs)]
  const fromIndex = nextTabs.findIndex((item) => item.path === fromPath)
  const toIndex = nextTabs.findIndex((item) => item.path === toPath)

  if (fromIndex <= 0 || toIndex <= 0) {
    return nextTabs
  }

  const [moved] = nextTabs.splice(fromIndex, 1)
  nextTabs.splice(toIndex, 0, moved)
  return ensureDashboardTab(nextTabs)
}
