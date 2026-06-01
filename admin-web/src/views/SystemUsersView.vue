<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>用户管理</h2>
        <p>维护后台账号、角色归属与可用状态。</p>
      </div>
      <n-space>
        <n-button @click="loadData">刷新</n-button>
        <n-button type="primary" @click="openCreate">新增用户</n-button>
      </n-space>
    </div>

    <n-card :bordered="false" size="small">
      <div class="toolbar">
        <n-input v-model:value="keyword" clearable placeholder="搜索用户名或显示名称" />
      </div>
      <n-data-table :columns="columns" :data="filteredUsers" :loading="loading" :pagination="false" />
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingUser ? '编辑用户' : '新增用户'"
      class="form-modal"
      :style="{ width: 'min(640px, calc(100vw - 32px))' }"
    >
      <n-form :model="formValue" label-placement="top">
        <n-form-item label="用户名">
          <n-input v-model:value="formValue.username" :disabled="Boolean(editingUser)" />
        </n-form-item>
        <n-form-item label="显示名称">
          <n-input v-model:value="formValue.displayName" />
        </n-form-item>
        <n-form-item v-if="!editingUser" label="密码">
          <n-input v-model:value="formValue.password" type="password" show-password-on="click" />
        </n-form-item>
        <n-form-item label="角色">
          <n-select v-model:value="formValue.roles" multiple :options="roleOptions" />
        </n-form-item>
        <n-form-item label="启用状态">
          <n-switch v-model:value="formValue.enabled" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showModal = false">取消</n-button>
          <n-button type="primary" @click="submitForm">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, SelectOption } from 'naive-ui'
import {
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NPopconfirm,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  useMessage
} from 'naive-ui'
import { createUser, deleteUser, fetchUsers, updateUser, updateUserStatus } from '../api/user'
import { fetchRoles } from '../api/role'
import type { RoleRecord, UserFormPayload, UserRecord } from '../types/rbac'

const message = useMessage()
const loading = ref(false)
const showModal = ref(false)
const keyword = ref('')
const users = ref<UserRecord[]>([])
const roles = ref<RoleRecord[]>([])
const editingUser = ref<UserRecord | null>(null)
const formValue = reactive<UserFormPayload>({
  username: '',
  displayName: '',
  password: 'agenthub123',
  enabled: true,
  roles: []
})

const roleOptions = computed<SelectOption[]>(() =>
  roles.value.map((item) => ({
    label: item.name,
    value: item.code
  }))
)

const filteredUsers = computed(() =>
  users.value.filter((item) =>
    !keyword.value ||
    item.username.includes(keyword.value) ||
    item.displayName.includes(keyword.value)
  )
)

const columns: DataTableColumns<UserRecord> = [
  { title: '用户名', key: 'username' },
  { title: '显示名称', key: 'displayName' },
  {
    title: '角色',
    key: 'roles',
    render: (row) => row.roles.join(' / ')
  },
  {
    title: '状态',
    key: 'enabled',
    render: (row) => h(
      NTag,
      { type: row.enabled ? 'success' : 'warning', bordered: false },
      { default: () => (row.enabled ? '启用' : '停用') }
    )
  },
  {
    title: '操作',
    key: 'actions',
    render: (row) =>
      h(NSpace, { wrap: false }, {
        default: () => [
          h(NButton, { size: 'small', tertiary: true, onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(
            NSwitch,
            {
              value: row.enabled,
              size: 'small',
              onUpdateValue: (value: boolean) => handleStatusChange(row, value)
            }
          ),
          h(
            NPopconfirm,
            { onPositiveClick: () => handleDelete(row.userId) },
            {
              trigger: () => h(NButton, { size: 'small', tertiary: true, type: 'error' }, { default: () => '删除' }),
              default: () => '确认删除当前用户吗？'
            }
          )
        ]
      })
  }
]

function resetForm() {
  formValue.username = ''
  formValue.displayName = ''
  formValue.password = 'agenthub123'
  formValue.enabled = true
  formValue.roles = roles.value[0] ? [roles.value[0].code] : []
}

function openCreate() {
  editingUser.value = null
  resetForm()
  showModal.value = true
}

function openEdit(user: UserRecord) {
  editingUser.value = user
  formValue.username = user.username
  formValue.displayName = user.displayName
  formValue.password = 'agenthub123'
  formValue.enabled = user.enabled
  formValue.roles = [...user.roles]
  showModal.value = true
}

async function loadData() {
  loading.value = true
  try {
    const [userData, roleData] = await Promise.all([fetchUsers(), fetchRoles()])
    users.value = userData
    roles.value = roleData
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载用户数据失败')
  } finally {
    loading.value = false
  }
}

async function submitForm() {
  try {
    if (editingUser.value) {
      await updateUser(editingUser.value.userId, {
        displayName: formValue.displayName,
        enabled: formValue.enabled,
        roles: formValue.roles
      })
      message.success('用户更新成功')
    } else {
      await createUser({ ...formValue })
      message.success('用户创建成功')
    }

    showModal.value = false
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存用户失败')
  }
}

async function handleStatusChange(user: UserRecord, enabled: boolean) {
  try {
    await updateUserStatus(user.userId, enabled)
    user.enabled = enabled
    message.success('状态已更新')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '更新状态失败')
  }
}

async function handleDelete(userId: number) {
  try {
    await deleteUser(userId)
    message.success('用户已删除')
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除用户失败')
  }
}

onMounted(() => {
  void loadData()
})
</script>

<style scoped>
.page {
  display: flex;
  flex: 1;
  min-height: 100%;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.page-header h2 {
  margin: 0 0 6px;
}

.page-header p {
  margin: 0;
  color: #64748b;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.toolbar :deep(.n-input) {
  max-width: 320px;
}

:deep(.n-card) {
  flex: 1;
}

:deep(.n-card__content) {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

@media (max-width: 900px) {
  .page-header,
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar :deep(.n-input) {
    max-width: none;
  }
}
</style>
