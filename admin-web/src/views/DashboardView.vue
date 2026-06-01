<template>
  <div class="dashboard">
    <section class="hero">
      <div class="hero-main">
        <span class="hero-eyebrow">管理工作台</span>
        <h2>{{ greetingTitle }}</h2>
        <p>
          当前登录账号为 <strong>{{ authStore.profile?.username ?? '--' }}</strong>，
          你可以从这里快速进入 RBAC 管理工作。
        </p>
        <div class="hero-tags">
          <n-tag type="primary" :bordered="false">
            {{ authStore.profile?.displayName ?? '未登录用户' }}
          </n-tag>
          <n-tag v-for="role in authStore.profile?.roles ?? []" :key="role" :bordered="false">
            {{ role }}
          </n-tag>
        </div>
      </div>

      <div class="hero-side">
        <div class="hero-side-label">当前状态</div>
        <div class="hero-side-value">{{ tabsStore.tabs.length }} 个页面已打开</div>
        <div class="hero-side-text">
          当前可访问 {{ menuCount }} 个菜单节点，适合继续处理账号、角色和菜单配置。
        </div>
      </div>
    </section>

    <section class="metric-grid">
      <n-card v-for="item in summaryCards" :key="item.key" :bordered="false" size="small">
        <div class="metric-card">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.tip }}</small>
        </div>
      </n-card>
    </section>

    <section class="content-grid">
      <n-card :bordered="false" size="small" title="快捷入口">
        <div class="entry-grid">
          <button
            v-for="entry in quickEntries"
            :key="entry.path"
            class="entry-card"
            type="button"
            @click="go(entry.path)"
          >
            <strong>{{ entry.title }}</strong>
            <span>{{ entry.description }}</span>
          </button>
        </div>
      </n-card>

      <n-card :bordered="false" size="small" title="当前账号概览">
        <div class="overview-grid">
          <div class="overview-block">
            <span>角色列表</span>
            <strong>{{ (authStore.profile?.roles ?? []).join(' / ') || '暂无角色' }}</strong>
          </div>
          <div class="overview-block">
            <span>可访问模块</span>
            <strong>{{ visibleModules || '暂无模块' }}</strong>
          </div>
          <div class="overview-block">
            <span>权限摘要</span>
            <strong>已具备 {{ authStore.profile?.permissions.length ?? 0 }} 个权限点</strong>
          </div>
        </div>
      </n-card>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { NCard, NTag } from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { useMenuStore } from '../stores/menu'
import { useTabsStore } from '../stores/tabs'
import { buildDashboardMetrics, buildQuickEntries, countMenuNodes } from './dashboard.helpers'

const router = useRouter()
const authStore = useAuthStore()
const menuStore = useMenuStore()
const tabsStore = useTabsStore()

const summaryCards = computed(() =>
  buildDashboardMetrics(authStore.profile, menuStore.userMenus, tabsStore.tabs)
)

const quickEntries = buildQuickEntries()

const menuCount = computed(() => countMenuNodes(menuStore.userMenus))

const visibleModules = computed(() =>
  menuStore.userMenus
    .flatMap((item) => item.children?.length ? item.children.map((child) => child.label) : [item.label])
    .join(' / ')
)

const greetingTitle = computed(() => {
  if (authStore.profile?.displayName) {
    return `${authStore.profile.displayName}，欢迎回来`
  }
  return '欢迎使用 Agent Hub'
})

function go(path: string) {
  void router.push(path)
}
</script>

<style scoped>
.dashboard {
  display: grid;
  flex: 1;
  min-height: 100%;
  gap: 16px;
}

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(280px, 0.9fr);
  gap: 16px;
  padding: 24px 28px;
  border: 1px solid #e7ecf5;
  border-radius: 8px;
  background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
}

.hero-main {
  display: grid;
  gap: 12px;
}

.hero-eyebrow,
.metric-card span,
.metric-card small,
.hero-side-label,
.hero-side-text,
.overview-block span,
.entry-card span {
  color: #64748b;
}

.hero-eyebrow {
  font-size: 13px;
  font-weight: 600;
}

.hero h2 {
  margin: 0;
  font-size: 30px;
}

.hero p {
  margin: 0;
  max-width: 720px;
  line-height: 1.7;
  color: #475569;
}

.hero-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.hero-side {
  display: grid;
  align-content: start;
  gap: 10px;
  padding: 18px 20px;
  border-radius: 8px;
  background: #f1f6ff;
}

.hero-side-value {
  font-size: 24px;
  font-weight: 700;
  color: #1e3a8a;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.metric-card {
  display: grid;
  gap: 10px;
}

.metric-card strong {
  font-size: 30px;
  line-height: 1;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(320px, 0.85fr);
  gap: 16px;
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.entry-card {
  display: grid;
  gap: 10px;
  padding: 18px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.2s ease, transform 0.2s ease, background-color 0.2s ease;
}

.entry-card:hover {
  border-color: #93c5fd;
  background: #eff6ff;
  transform: translateY(-1px);
}

.entry-card strong,
.overview-block strong {
  color: #0f172a;
}

.overview-grid {
  display: grid;
  gap: 12px;
}

.overview-block {
  display: grid;
  gap: 8px;
  padding: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

@media (max-width: 1080px) {
  .metric-grid,
  .entry-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .content-grid,
  .hero {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .hero {
    padding: 20px;
  }

  .hero h2 {
    font-size: 24px;
  }

  .metric-grid,
  .entry-grid {
    grid-template-columns: 1fr;
  }
}
</style>
