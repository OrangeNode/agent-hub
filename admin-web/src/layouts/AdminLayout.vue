<template>
  <n-layout has-sider class="admin-layout">
    <n-layout-sider
      bordered
      class="admin-sider"
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

    <n-layout class="layout-main">
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
        <div class="tab-scroll">
          <div
            v-for="item in tabsStore.tabs"
            :key="item.key"
            class="tab-chip"
            :class="{
              active: item.path === tabsStore.activeTab,
              fixed: !item.closable,
              dragging: draggedTabPath === item.path
            }"
            :draggable="item.closable"
            @click="handleTabClick(item.path)"
            @contextmenu.prevent="openTabContextMenu($event, item.path)"
            @dragstart="handleDragStart(item.path)"
            @dragover.prevent="handleDragOver(item.path)"
            @drop.prevent="handleDrop(item.path)"
            @dragend="handleDragEnd"
          >
            <span class="tab-chip__label">{{ item.title }}</span>
            <button
              v-if="item.closable"
              class="tab-chip__close"
              type="button"
              @click.stop="handleCloseTab(item.path)"
            >
              ×
            </button>
          </div>
        </div>

        <n-dropdown
          trigger="manual"
          placement="bottom-start"
          :show="showContextMenu"
          :x="contextMenuX"
          :y="contextMenuY"
          :options="contextMenuOptions"
          @clickoutside="closeContextMenu"
          @select="handleContextMenuSelect"
        />
      </div>

      <n-layout-content class="content-shell" content-style="padding: 18px 18px 20px;">
        <div class="content-body">
          <router-view />
        </div>
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { DropdownOption, MenuOption } from 'naive-ui'
import { MenuOutline } from '@vicons/ionicons5'
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
  NMenu
} from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { useMenuStore } from '../stores/menu'
import { useTabsStore } from '../stores/tabs'
import { resolveMenuIcon } from '../views/menu-icon-registry'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const menuStore = useMenuStore()
const tabsStore = useTabsStore()
const collapsed = ref(false)
const draggedTabPath = ref('')
const contextTabPath = ref('/dashboard')
const showContextMenu = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)

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

const contextMenuOptions = computed<DropdownOption[]>(() => {
  const isDashboard = contextTabPath.value === '/dashboard'

  return [
    { label: '关闭当前', key: 'close-current', disabled: isDashboard },
    { label: '关闭其他', key: 'close-others' },
    { label: '关闭右侧', key: 'close-right' },
    { label: '关闭全部', key: 'close-all' }
  ]
})

function renderIcon(name?: string | null) {
  const icon = resolveMenuIcon(name)
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
  tabsStore.setActiveTab(path)
  void router.push(path)
}

function handleCloseTab(path: string) {
  const fallback = tabsStore.removeTab(path)
  if (route.path === path) {
    void router.push(fallback)
  }
}

function handleDragStart(path: string) {
  draggedTabPath.value = path
}

function handleDragOver(path: string) {
  if (!draggedTabPath.value || draggedTabPath.value === path) {
    return
  }

  tabsStore.moveTab(draggedTabPath.value, path)
}

function handleDrop(path: string) {
  if (draggedTabPath.value) {
    tabsStore.moveTab(draggedTabPath.value, path)
  }
  draggedTabPath.value = ''
}

function handleDragEnd() {
  draggedTabPath.value = ''
}

function openTabContextMenu(event: MouseEvent, path: string) {
  contextTabPath.value = path
  contextMenuX.value = event.clientX
  contextMenuY.value = event.clientY
  showContextMenu.value = true
}

function closeContextMenu() {
  showContextMenu.value = false
}

function handleContextMenuSelect(key: string) {
  closeContextMenu()

  let targetPath = route.path

  if (key === 'close-current') {
    targetPath = tabsStore.removeTab(contextTabPath.value)
  } else if (key === 'close-others') {
    targetPath = tabsStore.closeOtherTabs(contextTabPath.value)
  } else if (key === 'close-right') {
    targetPath = tabsStore.closeTabsToRight(contextTabPath.value)
  } else if (key === 'close-all') {
    targetPath = tabsStore.closeAllTabs()
  }

  if (targetPath !== route.path) {
    void router.push(targetPath)
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
  height: 100vh;
  overflow: hidden;
}

.admin-sider {
  height: 100vh;
}

.layout-main {
  display: flex;
  min-width: 0;
  height: 100vh;
  flex-direction: column;
}

.content-shell {
  flex: 1;
  min-height: 0;
}

.content-body {
  display: flex;
  min-height: 100%;
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
  position: relative;
  display: flex;
  align-items: center;
  min-height: 46px;
  padding: 0 16px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}

.tab-scroll {
  display: flex;
  gap: 8px;
  width: 100%;
  overflow-x: auto;
  padding: 8px 0;
}

.tab-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  min-width: 0;
  max-width: 220px;
  padding: 0 12px;
  height: 30px;
  border: 1px solid #dbe3ef;
  border-radius: 6px;
  background: #f8fafc;
  color: #334155;
  cursor: pointer;
  user-select: none;
  transition: all 0.18s ease;
}

.tab-chip:hover {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.tab-chip.active {
  border-color: #93c5fd;
  background: #dbeafe;
  color: #1d4ed8;
}

.tab-chip.fixed {
  font-weight: 600;
}

.tab-chip.dragging {
  opacity: 0.6;
}

.tab-chip__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tab-chip__close {
  display: inline-grid;
  place-items: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.tab-chip__close:hover {
  background: rgba(148, 163, 184, 0.18);
}

:deep(.n-layout-sider-scroll-container) {
  display: flex;
  height: 100%;
  flex-direction: column;
}

:deep(.n-menu) {
  flex: 1;
  min-height: 0;
}

:deep(.n-layout-scroll-container) {
  height: 100%;
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
