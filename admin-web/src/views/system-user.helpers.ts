import type { UserSearchForm } from '../types/rbac'

export function buildUserSearchParams(filters: UserSearchForm) {
  const params: Record<string, string> = {}

  if (filters.username.trim()) {
    params.username = filters.username.trim()
  }

  if (filters.displayName.trim()) {
    params.displayName = filters.displayName.trim()
  }

  if (filters.enabled !== null) {
    params.enabled = filters.enabled
  }

  if (filters.roleCode) {
    params.roleCode = filters.roleCode
  }

  return params
}
