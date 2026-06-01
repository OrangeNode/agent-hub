import type { TreeSelectOption } from 'naive-ui'
import type { MenuRecord } from '../types/rbac'

export function buildMenuTableData(records: MenuRecord[]): MenuRecord[] {
  return [...records]
    .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
    .map((item) => ({
      ...item,
      children: item.children?.length ? buildMenuTableData(item.children) : undefined
    }))
}

export function buildMenuParentOptions(records: MenuRecord[]): TreeSelectOption[] {
  return buildMenuTableData(records).map((item) => ({
    label: item.title,
    key: item.menuId ?? 0,
    value: item.menuId ?? 0,
    children: item.children?.length ? buildMenuParentOptions(item.children) : undefined
  }))
}
