<template>
  <div class="login-page">
    <section class="hero">
      <div class="hero-copy">
        <span class="eyebrow">Agent Hub 用户端</span>
        <h1>登录后继续你的会话工作流</h1>
        <p>这里提供登录入口、会话导航和 SSE 流式聊天基础体验。</p>
      </div>
    </section>

    <section class="panel">
      <n-h2 style="margin: 0">账号登录</n-h2>
      <n-p depth="3" style="margin-top: 8px">当前为初始化骨架，输入任意用户名即可进入。</n-p>

      <n-form ref="formRef" :model="formValue" :rules="rules" size="large">
        <n-form-item path="username" label="用户名">
          <n-input v-model:value="formValue.username" placeholder="请输入用户名" />
        </n-form-item>

        <n-form-item path="password" label="密码">
          <n-input
            v-model:value="formValue.password"
            type="password"
            show-password-on="click"
            placeholder="请输入密码"
          />
        </n-form-item>

        <n-button block type="primary" :loading="submitting" @click="handleLogin">登录</n-button>
      </n-form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage, NButton, NForm, NFormItem, NH2, NInput, NP } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'

import { useAuthStore } from '../stores/auth'

const router = useRouter()
const message = useMessage()
const authStore = useAuthStore()
const formRef = ref<FormInst | null>(null)
const submitting = ref(false)

const formValue = reactive({
  username: '',
  password: ''
})

const rules: FormRules = {
  username: [
    {
      required: true,
      message: '请输入用户名',
      trigger: ['input', 'blur']
    }
  ],
  password: [
    {
      required: true,
      message: '请输入密码',
      trigger: ['input', 'blur']
    }
  ]
}

async function handleLogin() {
  await formRef.value?.validate()

  submitting.value = true

  window.setTimeout(() => {
    const username = formValue.username.trim()
    authStore.login(username, `mock-token-${username || 'user'}`)
    message.success('登录成功')
    submitting.value = false
    void router.push('/')
  }, 300)
}
</script>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(360px, 420px);
  min-height: 100vh;
  background: #f8fafc;
}

.hero {
  display: flex;
  align-items: flex-end;
  padding: 56px;
  background:
    linear-gradient(135deg, rgba(15, 23, 42, 0.92), rgba(30, 41, 59, 0.88)),
    url('https://images.unsplash.com/photo-1516321318423-f06f85e504b3?auto=format&fit=crop&w=1600&q=80')
      center / cover;
  color: #f8fafc;
}

.hero-copy {
  max-width: 520px;
  padding-bottom: 32px;
}

.eyebrow {
  display: inline-block;
  margin-bottom: 16px;
  color: #cbd5e1;
  font-size: 13px;
}

.hero-copy h1 {
  margin: 0;
  font-size: 42px;
  line-height: 1.1;
}

.hero-copy p {
  margin: 16px 0 0;
  color: #e2e8f0;
  font-size: 16px;
  line-height: 1.7;
}

.panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px;
  background: #fff;
}

@media (max-width: 960px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .hero {
    min-height: 40vh;
    padding: 32px 24px;
  }

  .hero-copy h1 {
    font-size: 32px;
  }

  .panel {
    padding: 32px 24px;
  }
}
</style>
