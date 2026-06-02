<template>
  <main class="home-page">
    <section class="home-card">
      <div class="brand-mark">A</div>
      <p class="eyebrow">Agent Hub 用户端</p>
      <h1>开始一次新的 Agent 对话</h1>
      <p class="description">
        用户端将以聊天为主入口，并在右侧扩展任务进度、文件预览和上下文面板。
      </p>
      <n-button type="primary" size="large" @click="goChat">进入聊天</n-button>
    </section>
  </main>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'

const router = useRouter()

function goChat() {
  void router.push('/chat')
}
</script>

<style scoped>
.home-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 24px;
  background: #ffffff;
}

.home-card {
  display: grid;
  justify-items: center;
  gap: 16px;
  width: min(100%, 620px);
  padding: 48px 32px;
  text-align: center;
}

.brand-mark {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border-radius: 999px;
  background: #202123;
  color: #ffffff;
  font-size: 20px;
  font-weight: 700;
}

.eyebrow {
  margin: 0;
  color: #6b6f76;
  font-size: 14px;
}

h1 {
  margin: 0;
  color: #202123;
  font-size: clamp(32px, 6vw, 52px);
  line-height: 1.08;
  letter-spacing: -0.04em;
}

.description {
  max-width: 520px;
  margin: 0;
  color: #565869;
  font-size: 16px;
  line-height: 1.8;
}
</style>
