<template>
  <div class="dashboard">
    <div class="hero">
      <div>
        <h2>{{ authStore.profile?.displayName ?? '欢迎使用 Agent Hub' }}</h2>
        <p>当前登录账号：{{ authStore.profile?.username ?? '--' }}</p>
      </div>
      <n-tag type="primary" :bordered="false">
        {{ authStore.profile?.roles.join(' / ') ?? '访客' }}
      </n-tag>
    </div>

    <div class="summary-grid">
      <n-card v-for="item in summaryCards" :key="item.label" size="small">
        <div class="summary-card">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.tip }}</small>
        </div>
      </n-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NCard, NTag } from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { useMenuStore } from '../stores/menu'

const authStore = useAuthStore()
const menuStore = useMenuStore()

const summaryCards = computed(() => [
  {
    label: '当前角色',
    value: authStore.profile?.roles.length ?? 0,
    tip: '已分配角色数'
  },
  {
    label: '授权菜单',
    value: menuStore.userMenus.length,
    tip: '当前账号可见导航'
  },
  {
    label: '权限点',
    value: authStore.profile?.permissions.length ?? 0,
    tip: '当前账号拥有的权限'
  }
])
</script>

<style scoped>
.dashboard {
  display: grid;
  gap: 16px;
}

.hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}

.hero h2 {
  margin: 0 0 8px;
}

.hero p,
.summary-card span,
.summary-card small {
  color: #64748b;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.summary-card {
  display: grid;
  gap: 8px;
}

.summary-card strong {
  font-size: 28px;
}

@media (max-width: 900px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .hero {
    flex-direction: column;
  }
}
</style>
