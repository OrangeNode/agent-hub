<template>
  <div class="login-page">
    <div class="login-panel">
      <div class="panel-copy">
        <span class="eyebrow">企业级 AI Agent 平台</span>
        <h1>管理端登录</h1>
        <p>使用系统管理员或运营角色账号进入 RBAC 管理工作台。</p>
      </div>

      <n-card class="login-card" :bordered="false">
        <n-form ref="formRef" :model="formValue" :rules="rules" @submit.prevent="handleLogin">
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
          <n-space vertical :size="12">
            <n-button type="primary" block :loading="authStore.loading" attr-type="submit">
              登录
            </n-button>
            <div class="tips">
              默认账号：admin / admin123
            </div>
          </n-space>
        </n-form>
      </n-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage, type FormInst, type FormRules } from 'naive-ui'
import { NButton, NCard, NForm, NFormItem, NInput, NSpace } from 'naive-ui'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const authStore = useAuthStore()
const formRef = ref<FormInst | null>(null)

const formValue = reactive({
  username: 'admin',
  password: 'admin123'
})

const rules: FormRules = {
  username: {
    required: true,
    message: '请输入用户名',
    trigger: ['blur', 'input']
  },
  password: {
    required: true,
    message: '请输入密码',
    trigger: ['blur', 'input']
  }
}

async function handleLogin() {
  await formRef.value?.validate()

  try {
    await authStore.loginWithPassword(formValue)
    message.success('登录成功')
    await router.push(String(route.query.redirect ?? '/dashboard'))
  } catch (error) {
    message.error(error instanceof Error ? error.message : '登录失败')
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
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.18), transparent 30%),
    #f5f7fb;
}

.login-panel {
  display: grid;
  grid-template-columns: minmax(280px, 380px) minmax(320px, 420px);
  gap: 24px;
  width: min(920px, 100%);
  align-items: center;
}

.panel-copy h1 {
  margin: 10px 0 12px;
  font-size: 36px;
}

.panel-copy p,
.eyebrow,
.tips {
  color: #64748b;
}

.eyebrow {
  font-size: 13px;
}

.login-card {
  border-radius: 8px;
}

@media (max-width: 900px) {
  .login-panel {
    grid-template-columns: 1fr;
  }
}
</style>
