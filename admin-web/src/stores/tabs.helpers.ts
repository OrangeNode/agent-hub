import type { TabRouteLike } from '../types/rbac'

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
