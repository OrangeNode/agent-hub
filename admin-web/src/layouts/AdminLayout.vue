<template>
  <n-layout has-sider class="admin-layout">
    <n-layout-sider
      bordered
      collapse-mode="width"
      :collapsed="collapsed"
      :collapsed-width="72"
      :width="236"
      :native-scrollbar="false"
    >
      <div class="brand" @click="goDashboard">
        <div class="brand-mark">AH</div>
        <div v-if="!collapsed" class="brand-copy">
          <strong>Agent Hub</strong>
          <span>管理工作台</span>
        </div>
      </div>

      <n-menu
        :collapsed="collapsed"
        :collapsed-width="72"
        :collapsed-icon-size="18"
        :options="menuOptions"
        :value="selectedKey"
        @update:value="handleSelect"
      />
    </n-layout-sider>

    <n-layout>
      <n-layout-header bordered class="header">
        <div class="header-left">
          <n-button quaternary circle @click="collapsed = !collapsed">
            <template #icon>
              <n-icon><MenuOutline /></n-icon>
            </template>
          </n-button>
          <n-breadcrumb>
            <n-breadcrumb-item v-for="item in breadcrumbs" :key="item.path">
              {{ item.label }}
            </n-breadcrumb-item>
          </n-breadcrumb>
        </div>

        <div class="header-right">
          <div class="operator">
            <strong>{{ authStore.profile?.displayName ?? '未登录' }}</strong>
            <span>{{ authStore.profile?.roles.join(' / ') ?? '访客' }}</span>
          </div>
          <n-dropdown :options="userOptions" @select="handleUserCommand">
            <n-button quaternary>
              {{ authStore.profile?.username ?? '账号' }}
            </n-button>
          </n-dropdown>
        </div>
      </n-layout-header>

      <div class="tab-bar">
        <n-tag
          v-for="item in tabsStore.tabs"
          :key="item.key"
          :bordered="false"
          class="tab-item"
          :class="{ active: item.path === tabsStore.activeTab }"
          :closable="item.closable"
          :on-close="item.closable ? ($event: MouseEvent) => handleCloseTab($event, item.path) : undefined"
          @click="handleTabClick(item.path)"
        >
          {{ item.title }}
        </n-tag>
      </div>

      <n-layout-content content-style="padding: 16px;">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { DropdownOption, MenuOption } from 'naive-ui'
import {
  GridOutline,
  ListOutline,
  MenuOutline,
  PeopleOutline,
  ShieldOutline
} from '@vicons/ionicons5'
import {
  NBreadcrumb,
  NBreadcrumbItem,
  NButton,
  NDropdown,
  NIcon,
  NLayout,
  NLayoutContent,
  NLayoutHeader,
  NLayoutSider,
  NMenu,
  NTag
} from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { useMenuStore } from '../stores/menu'
import { useTabsStore } from '../stores/tabs'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const menuStore = useMenuStore()
const tabsStore = useTabsStore()
const collapsed = ref(false)

const iconMap = {
  grid: GridOutline,
  people: PeopleOutline,
  shield: ShieldOutline,
  menu: MenuOutline,
  list: ListOutline,
  DashboardOutlined: GridOutline,
  UserOutlined: PeopleOutline,
  TeamOutlined: ShieldOutline,
  MenuOutlined: MenuOutline,
  SettingOutlined: ListOutline
} as const

const selectedKey = computed(() => {
  const matched = findMenuByPath(menuStore.userMenus, route.path)
  return matched?.key ?? String(route.name ?? '')
})

const menuOptions = computed<MenuOption[]>(() =>
  menuStore.menuOptions.map((item: { key: string; label: string; icon?: string; children?: Array<{ key: string; label: string; icon?: string }> }) => ({
    key: item.key,
    label: item.label,
    icon: renderIcon(item.icon),
    children: item.children?.map((child: { key: string; label: string; icon?: string }) => ({
      key: child.key,
      label: child.label,
      icon: renderIcon(child.icon)
    }))
  }))
)

const breadcrumbs = computed(() => {
  const current = findMenuByPath(menuStore.userMenus, route.path)

  if (!current) {
    return [{ path: route.path, label: String(route.meta.title ?? '工作台') }]
  }

  const section = route.path.startsWith('/system/') ? [{ path: '/system', label: '系统管理' }] : []
  return [...section, { path: current.routePath, label: current.label }]
})

const userOptions: DropdownOption[] = [
  {
    label: '刷新资料',
    key: 'refresh'
  },
  {
    label: '退出登录',
    key: 'logout'
  }
]

function renderIcon(name?: string | null) {
  if (!name) {
    return undefined
  }

  const icon = iconMap[name as keyof typeof iconMap]
  if (!icon) {
    return undefined
  }

  return () => h(NIcon, null, { default: () => h(icon) })
}

function findMenuByPath(records: Array<{ routePath: string; key: string; label: string; icon?: string; children?: any[] }>, path: string): any {
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

function handleSelect(key: string) {
  const matched = findMenuByKey(menuStore.userMenus, key)
  if (matched) {
    void router.push(matched.routePath)
  }
}

function findMenuByKey(records: Array<{ key: string; routePath: string; children?: any[] }>, key: string): any {
  for (const item of records) {
    if (item.key === key) {
      return item
    }

    if (item.children?.length) {
      const matched = findMenuByKey(item.children, key)
      if (matched) {
        return matched
      }
    }
  }

  return null
}

function handleTabClick(path: string) {
  void router.push(path)
}

function handleCloseTab(event: MouseEvent, path: string) {
  event.stopPropagation()
  const fallback = tabsStore.removeTab(path)
  if (route.path === path) {
    void router.push(fallback)
  }
}

async function handleUserCommand(key: string) {
  if (key === 'refresh') {
    await authStore.refreshProfile()
    await menuStore.loadUserMenus()
    return
  }

  authStore.logout()
  menuStore.clear()
  tabsStore.reset()
  await router.push('/login')
}

function goDashboard() {
  void router.push('/dashboard')
}
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  cursor: pointer;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: #2563eb;
  color: #fff;
  font-weight: 700;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.brand-copy span {
  color: #64748b;
  font-size: 12px;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 16px;
  height: 56px;
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.operator {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.operator span {
  color: #64748b;
  font-size: 12px;
}

.tab-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 0 16px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
  overflow-x: auto;
}

.tab-item {
  cursor: pointer;
}

.tab-item.active {
  background: #dbeafe;
  color: #1d4ed8;
}

@media (max-width: 960px) {
  .header {
    height: auto;
    padding: 12px 16px;
    flex-direction: column;
    align-items: flex-start;
  }

  .header-right {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
