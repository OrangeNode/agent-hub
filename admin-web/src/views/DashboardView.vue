<template>
  <div class="dashboard">
    <section class="overview">
      <div class="hero-panel">
        <div>
          <span class="caption">平台总览</span>
          <h2>管理端初始化骨架已就绪</h2>
          <p>当前页面用于承接系统管理、AI 配置、扩展能力与 Agent 目录入口。</p>
        </div>
        <n-space>
          <n-tag type="success" round>类型检查目标已纳入</n-tag>
          <n-tag type="info" round>构建目标已纳入</n-tag>
        </n-space>
      </div>

      <div class="stat-grid">
        <n-card v-for="item in statCards" :key="item.label" size="small">
          <div class="stat-card">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
            <small>{{ item.tip }}</small>
          </div>
        </n-card>
      </div>
    </section>

    <section class="panel-grid">
      <n-card title="当前登录信息" size="small">
        <n-descriptions label-placement="left" :column="1" bordered>
          <n-descriptions-item label="显示名称">
            {{ authStore.profile?.displayName ?? '未登录' }}
          </n-descriptions-item>
          <n-descriptions-item label="用户名">
            {{ authStore.profile?.username ?? '-' }}
          </n-descriptions-item>
          <n-descriptions-item label="角色">
            {{ authStore.profile?.roles.join(' / ') ?? '-' }}
          </n-descriptions-item>
          <n-descriptions-item label="权限数">
            {{ authStore.profile?.permissions.length ?? 0 }}
          </n-descriptions-item>
        </n-descriptions>
      </n-card>

      <n-card title="模块导航" size="small">
        <div class="quick-links">
          <n-button
            v-for="item in quickLinks"
            :key="item.key"
            secondary
            type="primary"
            @click="handleJump(item.key)"
          >
            {{ item.title }}
          </n-button>
        </div>
      </n-card>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NCard,
  NDescriptions,
  NDescriptionsItem,
  NSpace,
  NTag
} from 'naive-ui'
import { moduleDefinitions } from '../config/module-routes'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const statCards = computed(() => [
  { label: '管理模块', value: moduleDefinitions.length, tip: '已接入骨架路由' },
  { label: '系统分区', value: new Set(moduleDefinitions.map((item) => item.section)).size, tip: '按后台导航分组' },
  { label: '当前权限', value: authStore.profile?.permissions.length ?? 0, tip: '来源于本地鉴权状态' },
  { label: '演示账号', value: 2, tip: 'admin / user 两套启动身份' }
])

const quickLinks = computed(() => moduleDefinitions.slice(0, 6))

function handleJump(key: string) {
  void router.push({ name: key })
}
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.overview,
.panel-grid {
  display: grid;
  gap: 20px;
}

.hero-panel {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 24px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #e2e8f0;
}

.caption {
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 600;
}

.hero-panel h2 {
  margin: 10px 0 8px;
  font-size: 24px;
}

.hero-panel p {
  margin: 0;
  color: #64748b;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stat-card span,
.stat-card small {
  color: #64748b;
}

.stat-card strong {
  font-size: 28px;
}

.panel-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.quick-links {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

@media (max-width: 960px) {
  .hero-panel,
  .panel-grid {
    grid-template-columns: 1fr;
    display: grid;
  }

  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
