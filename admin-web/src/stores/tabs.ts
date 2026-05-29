import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { TabItem, TabRouteLike } from '../types/rbac'
import { resolveTabTitle } from './tabs.helpers'

export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<TabItem[]>([])
  const activeTab = ref('')

  const visibleTabs = computed(() => tabs.value)

  function addTab(route: TabRouteLike) {
    const key = typeof route.name === 'string' && route.name ? route.name : route.path
    const existing = tabs.value.find((item) => item.key === key)
    activeTab.value = route.path

    if (existing) {
      existing.path = route.path
      existing.title = resolveTabTitle(route)
      return
    }

    tabs.value.push({
      key,
      title: resolveTabTitle(route),
      path: route.path,
      closable: route.path !== '/dashboard'
    })
  }

  function removeTab(path: string) {
    const index = tabs.value.findIndex((item) => item.path === path)

    if (index === -1) {
      return '/dashboard'
    }

    const fallback = tabs.value[index - 1] ?? tabs.value[index + 1]
    tabs.value.splice(index, 1)
    activeTab.value = fallback?.path ?? '/dashboard'
    return activeTab.value
  }

  function reset() {
    tabs.value = []
    activeTab.value = ''
  }

  return {
    tabs: visibleTabs,
    activeTab,
    addTab,
    removeTab,
    reset
  }
})
