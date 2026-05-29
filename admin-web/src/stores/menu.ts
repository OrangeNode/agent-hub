import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchMenuTree, fetchUserMenus } from '../api/menu'
import { buildNaiveMenuOptions } from './menu.helpers'
import type { MenuRecord, MenuTreeNode } from '../types/rbac'

export const useMenuStore = defineStore('menu', () => {
  const rawMenus = ref<MenuRecord[]>([])
  const userMenus = ref<MenuTreeNode[]>([])
  const loading = ref(false)

  const hasMenus = computed(() => userMenus.value.length > 0)
  const menuOptions = computed(() => buildNaiveMenuOptions(userMenus.value))

  async function loadAllMenus() {
    rawMenus.value = await fetchMenuTree()
    return rawMenus.value
  }

  async function loadUserMenus() {
    loading.value = true

    try {
      userMenus.value = await fetchUserMenus()
      return userMenus.value
    } finally {
      loading.value = false
    }
  }

  function clear() {
    rawMenus.value = []
    userMenus.value = []
  }

  return {
    rawMenus,
    userMenus,
    loading,
    hasMenus,
    menuOptions,
    loadAllMenus,
    loadUserMenus,
    clear
  }
})
