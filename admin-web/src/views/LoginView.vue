<template>
  <div class="login-page">
    <n-card class="login-card" :bordered="false">
      <div class="hero-copy">
        <span class="eyebrow">Agent Hub</span>
        <h1>管理端登录</h1>
        <p>用于初始化平台管理员、权限配置和智能体目录的统一入口。</p>
      </div>

      <n-form ref="formRef" :model="formValue" :rules="rules" @submit.prevent="handleSubmit">
        <n-form-item label="用户名" path="username">
          <n-input v-model:value="formValue.username" placeholder="请输入用户名" />
        </n-form-item>
        <n-form-item label="密码" path="password">
          <n-input
            v-model:value="formValue.password"
            type="password"
            show-password-on="click"
            placeholder="请输入密码"
          />
        </n-form-item>

        <div class="tips">
          <span>演示账号：admin / admin123</span>
          <span>只做管理端骨架验证，不依赖后端服务在线。</span>
        </div>

        <n-button
          type="primary"
          block
          attr-type="submit"
          :loading="authStore.loading"
        >
          登录管理端
        </n-button>
      </n-form>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage, type FormInst, type FormRules } from 'naive-ui'
import { NButton, NCard, NForm, NFormItem, NInput } from 'naive-ui'
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
  username: [{ required: true, message: '请输入用户名', trigger: ['blur', 'input'] }],
  password: [{ required: true, message: '请输入密码', trigger: ['blur', 'input'] }]
}

async function handleSubmit() {
  await formRef.value?.validate()

  try {
    await authStore.loginWithPassword(formValue)
    message.success('登录成功，正在进入管理台')

    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    void router.push(redirect)
  } catch (error) {
    const content = error instanceof Error ? error.message : '登录失败，请稍后重试'
    message.error(content)
  }
}
</script>

<style scoped>
.login-page {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 24px;
  background:
    linear-gradient(180deg, rgba(29, 78, 216, 0.08), rgba(15, 23, 42, 0.02)),
    #f3f6fb;
}

.login-card {
  width: min(420px, 100%);
  border-radius: 8px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
}

.hero-copy {
  margin-bottom: 20px;
}

.eyebrow {
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 600;
}

.hero-copy h1 {
  margin: 10px 0 8px;
  font-size: 28px;
}

.hero-copy p {
  margin: 0;
  color: #64748b;
  line-height: 1.6;
}

.tips {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
  color: #64748b;
  font-size: 12px;
}
</style>
