<template>
  <n-layout has-sider class="admin-layout">
    <n-layout-sider
      bordered
      collapse-mode="width"
      :collapsed-width="72"
      :width="248"
      :native-scrollbar="false"
      show-trigger
    >
      <div class="brand">
        <div class="brand-mark">AH</div>
        <div v-if="!collapsed" class="brand-copy">
          <strong>Agent Hub</strong>
          <span>管理控制台</span>
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
        <div>
          <div class="page-title">{{ currentTitle }}</div>
          <div class="page-subtitle">企业级 AI Agent 平台初始化骨架</div>
        </div>

        <div class="header-actions">
          <div class="operator">
            <strong>{{ authStore.profile?.displayName ?? '未登录' }}</strong>
            <span>{{ authStore.profile?.roles.join(' / ') ?? '访客' }}</span>
          </div>
          <n-button tertiary @click="handleRefresh">刷新资料</n-button>
          <n-button type="error" ghost @click="handleLogout">退出登录</n-button>
        </div>
      </n-layout-header>

      <n-layout-content content-style="padding: 20px;">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MenuOption } from 'naive-ui'
import {
  NButton,
  NLayout,
  NLayoutContent,
  NLayoutHeader,
  NLayoutSider,
  NMenu
} from 'naive-ui'
import { navigationGroups } from '../config/module-routes'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const collapsed = ref(false)

const menuOptions = computed<MenuOption[]>(() => [
  {
    label: '仪表盘',
    key: 'dashboard'
  },
  ...navigationGroups.map(([section, items]) => ({
    label: section,
    key: section,
    children: items.map((item) => ({
      label: item.title,
      key: item.key
    }))
  }))
])

const selectedKey = computed(() => (typeof route.name === 'string' ? route.name : 'dashboard'))
const currentTitle = computed(() => route.meta.title ?? '管理控制台')

function handleSelect(key: string) {
  void router.push({ name: key })
}

async function handleRefresh() {
  await authStore.refreshProfile()
}

function handleLogout() {
  authStore.logout()
  void router.push({ name: 'login' })
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
  padding: 18px 16px;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: #1d4ed8;
  color: #fff;
  font-weight: 700;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.brand-copy strong {
  font-size: 15px;
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
  padding: 16px 20px;
  background: #fff;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
}

.page-subtitle {
  margin-top: 4px;
  color: #64748b;
  font-size: 13px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.operator {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  min-width: 120px;
}

.operator span {
  color: #64748b;
  font-size: 12px;
}

@media (max-width: 960px) {
  .header {
    flex-direction: column;
    align-items: flex-start;
  }

  .header-actions {
    width: 100%;
    flex-wrap: wrap;
  }

  .operator {
    align-items: flex-start;
  }
}
</style>
