import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { TabItem, TabRouteLike } from '../types/rbac'
import { DASHBOARD_TAB, ensureDashboardTab, moveTabWithinClosableList, resolveTabTitle } from './tabs.helpers'

export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<TabItem[]>([{ ...DASHBOARD_TAB }])
  const activeTab = ref('/dashboard')

  const visibleTabs = computed(() => tabs.value)

  function syncTabs(nextTabs: TabItem[]) {
    tabs.value = ensureDashboardTab(nextTabs)
  }

  function addTab(route: TabRouteLike) {
    const key = typeof route.name === 'string' && route.name ? route.name : route.path
    const existing = tabs.value.find((item) => item.key === key)
    activeTab.value = route.path

    if (existing) {
      existing.path = route.path
      existing.title = resolveTabTitle(route)
      return
    }

    syncTabs([...tabs.value, {
      key,
      title: resolveTabTitle(route),
      path: route.path,
      closable: route.path !== '/dashboard'
    }])
  }

  function removeTab(path: string) {
    if (path === '/dashboard') {
      activeTab.value = '/dashboard'
      return activeTab.value
    }

    const index = tabs.value.findIndex((item) => item.path === path)

    if (index === -1) {
      return '/dashboard'
    }

    const fallback = tabs.value[index - 1] ?? tabs.value[index + 1]
    syncTabs(tabs.value.filter((item) => item.path !== path))
    activeTab.value = fallback?.path ?? '/dashboard'
    return activeTab.value
  }

  function setActiveTab(path: string) {
    activeTab.value = path
  }

  function moveTab(fromPath: string, toPath: string) {
    syncTabs(moveTabWithinClosableList(tabs.value, fromPath, toPath))
  }

  function closeOtherTabs(path: string) {
    const current = tabs.value.find((item) => item.path === path)
    syncTabs(current ? [current] : [{ ...DASHBOARD_TAB }])
    activeTab.value = current?.path ?? '/dashboard'
    return activeTab.value
  }

  function closeTabsToRight(path: string) {
    const index = tabs.value.findIndex((item) => item.path === path)
    if (index === -1) {
      return activeTab.value
    }

    syncTabs(tabs.value.slice(0, index + 1))
    activeTab.value = tabs.value.some((item) => item.path === activeTab.value) ? activeTab.value : path
    return activeTab.value
  }

  function closeAllTabs() {
    syncTabs([])
    activeTab.value = '/dashboard'
    return activeTab.value
  }

  function reset() {
    tabs.value = [{ ...DASHBOARD_TAB }]
    activeTab.value = '/dashboard'
  }

  return {
    tabs: visibleTabs,
    activeTab,
    addTab,
    removeTab,
    setActiveTab,
    moveTab,
    closeOtherTabs,
    closeTabsToRight,
    closeAllTabs,
    reset
  }
})
