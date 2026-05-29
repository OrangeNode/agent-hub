<template>
  <div class="home-page">
    <section class="overview">
      <div>
        <n-h2 style="margin: 0">欢迎回来，{{ authStore.username || '用户' }}</n-h2>
        <n-p depth="3" style="margin: 8px 0 0">
          当前骨架聚焦登录、会话列表和流式聊天入口，方便后续接入真实对话能力。
        </n-p>
      </div>

      <n-button type="primary" @click="goChat">进入聊天</n-button>
    </section>

    <section class="metrics">
      <article v-for="card in cards" :key="card.label" class="metric-card">
        <span class="metric-label">{{ card.label }}</span>
        <strong class="metric-value">{{ card.value }}</strong>
        <span class="metric-note">{{ card.note }}</span>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NH2, NP } from 'naive-ui'

import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const cards = computed(() => [
  {
    label: '会话状态',
    value: '已就绪',
    note: '聊天页已具备 SSE 连接入口'
  },
  {
    label: '当前身份',
    value: authStore.username || '未命名用户',
    note: '基于本地 Pinia 状态与本地存储'
  },
  {
    label: '交付范围',
    value: '最小骨架',
    note: '未定义 SSE 事件协议，仅做消息追加'
  }
])

function goChat() {
  void router.push('/chat')
}
</script>

<style scoped>
.home-page {
  display: grid;
  gap: 20px;
}

.overview {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 24px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.metric-card {
  display: grid;
  gap: 10px;
  padding: 20px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
}

.metric-label {
  color: #64748b;
  font-size: 13px;
}

.metric-value {
  color: #0f172a;
  font-size: 24px;
}

.metric-note {
  color: #475569;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .overview {
    flex-direction: column;
  }

  .metrics {
    grid-template-columns: 1fr;
  }
}
</style>
