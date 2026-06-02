<template>
  <main class="login-page">
    <section class="login-card">
      <div class="brand-mark">A</div>
      <div class="heading">
        <p>Agent Hub 用户端</p>
        <h1>登录后开始对话</h1>
        <span>当前为初始化骨架，输入任意用户名和密码即可进入。</span>
      </div>

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

        <n-button block type="primary" size="large" :loading="submitting" @click="handleLogin">
          登录
        </n-button>
      </n-form>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage, NButton, NForm, NFormItem, NInput } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'

import { login } from '../api/auth'
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

  try {
    const username = formValue.username.trim()
    const result = await login({
      username,
      password: formValue.password
    })
    authStore.login(result.token, result.profile)
    message.success('登录成功')
    void router.push('/chat')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '登录失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at 50% 0%, rgba(37, 99, 235, 0.1), transparent 34%),
    #ffffff;
}

.login-card {
  display: grid;
  gap: 26px;
  width: min(100%, 420px);
  padding: 34px;
  border: 1px solid #ececf1;
  border-radius: 22px;
  background: #ffffff;
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.1);
}

.brand-mark {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 999px;
  background: #2563eb;
  color: #ffffff;
  font-size: 20px;
  font-weight: 700;
}

.heading {
  display: grid;
  gap: 8px;
}

.heading p,
.heading h1,
.heading span {
  margin: 0;
}

.heading p {
  color: #2563eb;
  font-size: 13px;
  font-weight: 700;
}

.heading h1 {
  color: #202123;
  font-size: 28px;
  letter-spacing: -0.04em;
}

.heading span {
  color: #6b6f76;
  font-size: 14px;
  line-height: 1.7;
}

@media (max-width: 520px) {
  .login-card {
    padding: 24px;
  }
}
</style>
