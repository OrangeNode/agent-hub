export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface LoginPayload {
  username: string
  password: string
}

export interface UserProfile {
  userId: number
  username: string
  displayName: string
  enabled: boolean
  roles: string[]
  permissions: string[]
}

export interface LoginResult {
  token: string
  profile: UserProfile
}

export interface MenuRecord {
  menuId?: number
  parentId?: number | null
  component?: string | null
  icon?: string | null
  permission?: string | null
  menuType?: string
  visible?: boolean
  sort?: number
  path: string
  name: string
  title: string
  children?: MenuRecord[]
}

export interface MenuTreeNode {
  key: string
  menuId?: number
  label: string
  routePath: string
  routeName?: string
  sort?: number
  icon?: string
  permission?: string
  children?: MenuTreeNode[]
}

export interface UserRecord {
  userId: number
  username: string
  displayName: string
  roles: string[]
  enabled: boolean
  permissions: string[]
}

export interface RoleRecord {
  roleId: number
  code: string
  name: string
  enabled: boolean
  menuIds: number[]
}

export interface MenuFormRecord {
  menuId?: number
  parentId: number | null
  path: string
  name: string
  title: string
  icon?: string
  permission?: string
  component?: string
  menuType: string
  visible: boolean
  sort: number
}

export interface UserFormPayload {
  username: string
  displayName: string
  password: string
  enabled: boolean
  roles: string[]
}

export interface UserSearchForm {
  username: string
  displayName: string
  enabled: 'true' | 'false' | null
  roleCode: string | null
}

export interface RoleFormPayload {
  code: string
  name: string
  enabled: boolean
  menuIds: number[]
}

export interface MenuOrderPayload {
  menuId: number
  sort: number
}

export interface TabRouteLike {
  name?: string | symbol | null
  path: string
  meta?: Record<string, unknown>
}

export interface TabItem {
  key: string
  title: string
  path: string
  closable: boolean
}
