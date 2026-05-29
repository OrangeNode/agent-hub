import http from './http'
import type { ApiResponse, MenuFormRecord, MenuOrderPayload, MenuRecord } from '../types/rbac'
import { buildMenuTree } from '../stores/menu.helpers'

export async function fetchMenus() {
  const response = await http.get<ApiResponse<MenuRecord[]>>('/system/menus')
  return response.data.data
}

export async function fetchMenuTree() {
  const response = await http.get<ApiResponse<MenuRecord[]>>('/system/menus/tree')
  return response.data.data
}

export async function fetchUserMenus() {
  const response = await http.get<ApiResponse<MenuRecord[]>>('/system/menus/current-user')
  return buildMenuTree(response.data.data)
}

export async function createMenu(payload: MenuFormRecord) {
  const response = await http.post<ApiResponse<MenuRecord>>('/system/menus', payload)
  return response.data.data
}

export async function updateMenu(menuId: number, payload: MenuFormRecord) {
  const response = await http.put<ApiResponse<MenuRecord>>(`/system/menus/${menuId}`, payload)
  return response.data.data
}

export async function deleteMenu(menuId: number) {
  await http.delete(`/system/menus/${menuId}`)
}

export async function reorderMenus(payload: MenuOrderPayload[]) {
  await http.put('/system/menus/order', payload)
}
