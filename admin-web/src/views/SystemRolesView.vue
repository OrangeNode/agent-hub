<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>角色管理</h2>
        <p>维护角色编码、菜单授权和启停状态。</p>
      </div>
      <n-space>
        <n-button @click="loadData">刷新</n-button>
        <n-button type="primary" @click="openCreate">新增角色</n-button>
      </n-space>
    </div>

    <n-card :bordered="false" size="small">
      <div class="toolbar">
        <n-input v-model:value="keyword" clearable placeholder="搜索角色编码或名称" />
      </div>
      <n-data-table :columns="columns" :data="filteredRoles" :loading="loading" :pagination="false" />
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingRole ? '编辑角色' : '新增角色'"
      class="form-modal"
      :style="{ width: 'min(700px, calc(100vw - 32px))' }"
    >
      <n-form :model="formValue" label-placement="top">
        <n-form-item label="角色编码">
          <n-input v-model:value="formValue.code" :disabled="Boolean(editingRole)" />
        </n-form-item>
        <n-form-item label="角色名称">
          <n-input v-model:value="formValue.name" />
        </n-form-item>
        <n-form-item label="菜单授权">
          <n-tree-select
            v-model:value="formValue.menuIds"
            multiple
            check-strategy="child"
            :options="menuOptions"
            clearable
          />
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
import type { DataTableColumns, TreeSelectOption } from 'naive-ui'
import {
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NPopconfirm,
  NSpace,
  NSwitch,
  NTag,
  NTreeSelect,
  useMessage
} from 'naive-ui'
import { createRole, deleteRole, fetchRoles, updateRole } from '../api/role'
import { fetchMenuTree } from '../api/menu'
import type { MenuRecord, RoleFormPayload, RoleRecord } from '../types/rbac'

const message = useMessage()
const loading = ref(false)
const showModal = ref(false)
const keyword = ref('')
const roles = ref<RoleRecord[]>([])
const menus = ref<MenuRecord[]>([])
const editingRole = ref<RoleRecord | null>(null)
const formValue = reactive<RoleFormPayload>({
  code: '',
  name: '',
  enabled: true,
  menuIds: []
})

const filteredRoles = computed(() =>
  roles.value.filter((item) =>
    !keyword.value ||
    item.code.includes(keyword.value) ||
    item.name.includes(keyword.value)
  )
)

const menuOptions = computed<TreeSelectOption[]>(() => mapMenuOptions(menus.value))

const columns: DataTableColumns<RoleRecord> = [
  { title: '角色编码', key: 'code' },
  { title: '角色名称', key: 'name' },
  {
    title: '授权菜单数',
    key: 'menuIds',
    render: (row) => row.menuIds.length
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
            NPopconfirm,
            { onPositiveClick: () => handleDelete(row.roleId) },
            {
              trigger: () => h(NButton, { size: 'small', tertiary: true, type: 'error' }, { default: () => '删除' }),
              default: () => '确认删除当前角色吗？'
            }
          )
        ]
      })
  }
]

function mapMenuOptions(records: MenuRecord[]): TreeSelectOption[] {
  return records.map((item) => ({
    label: item.title,
    key: item.menuId,
    value: item.menuId,
    children: item.children?.length ? mapMenuOptions(item.children) : undefined
  }))
}

function openCreate() {
  editingRole.value = null
  formValue.code = ''
  formValue.name = ''
  formValue.enabled = true
  formValue.menuIds = []
  showModal.value = true
}

function openEdit(role: RoleRecord) {
  editingRole.value = role
  formValue.code = role.code
  formValue.name = role.name
  formValue.enabled = role.enabled
  formValue.menuIds = [...role.menuIds]
  showModal.value = true
}

async function loadData() {
  loading.value = true
  try {
    const [roleData, menuData] = await Promise.all([fetchRoles(), fetchMenuTree()])
    roles.value = roleData
    menus.value = menuData
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载角色数据失败')
  } finally {
    loading.value = false
  }
}

async function submitForm() {
  try {
    if (editingRole.value) {
      await updateRole(editingRole.value.roleId, {
        name: formValue.name,
        enabled: formValue.enabled,
        menuIds: formValue.menuIds
      })
      message.success('角色更新成功')
    } else {
      await createRole({ ...formValue })
      message.success('角色创建成功')
    }

    showModal.value = false
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存角色失败')
  }
}

async function handleDelete(roleId: number) {
  try {
    await deleteRole(roleId)
    message.success('角色已删除')
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除角色失败')
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
