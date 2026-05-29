<template>
  <n-layout class="shell">
    <n-layout-sider
      bordered
      collapse-mode="width"
      :collapsed-width="72"
      :width="240"
      show-trigger
    >
      <div class="brand">
        <div class="brand-mark">AH</div>
        <div v-if="!collapsed" class="brand-copy">
          <strong>Agent Hub</strong>
          <span>用户工作台</span>
        </div>
      </div>

      <n-menu
        :collapsed="collapsed"
        :collapsed-width="72"
        :collapsed-icon-size="20"
        :options="menuOptions"
        :value="selectedKey"
        @update:value="handleSelect"
      />
    </n-layout-sider>

    <n-layout>
      <n-layout-header bordered class="header">
        <div>
          <div class="header-title">{{ pageTitle }}</div>
          <div class="header-subtitle">面向对话与会话协作的最小工作区</div>
        </div>

        <div class="header-actions">
          <n-tag type="info" size="small">{{ authStore.username || '访客' }}</n-tag>
          <n-button tertiary type="error" @click="handleLogout">退出登录</n-button>
        </div>
      </n-layout-header>

      <n-layout-content content-style="padding: 24px;">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NLayout, NLayoutContent, NLayoutHeader, NLayoutSider, NMenu, NTag } from 'naive-ui'
import type { MenuOption } from 'naive-ui'

import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const collapsed = ref(false)

const menuOptions: MenuOption[] = [
  {
    label: '首页',
    key: '/'
  },
  {
    label: '聊天',
    key: '/chat'
  }
]

const routeTitleMap: Record<string, string> = {
  '/': '首页',
  '/chat': '对话工作区'
}

const selectedKey = computed(() => (route.path === '/chat' ? '/chat' : '/'))
const pageTitle = computed(() => routeTitleMap[selectedKey.value] ?? '用户工作台')

function handleSelect(key: string) {
  void router.push(key)
}

function handleLogout() {
  authStore.logout()
  void router.push('/login')
}
</script>

<style scoped>
.shell {
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
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 8px;
  background: #1f2937;
  color: #f8fafc;
  font-weight: 700;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.brand-copy span {
  color: #64748b;
  font-size: 12px;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 72px;
  padding: 0 24px;
  background: #fff;
}

.header-title {
  color: #0f172a;
  font-size: 18px;
  font-weight: 600;
}

.header-subtitle {
  margin-top: 4px;
  color: #64748b;
  font-size: 13px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
