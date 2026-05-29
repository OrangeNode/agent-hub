<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>菜单管理</h2>
        <p>维护系统导航结构、排序和路由元数据。</p>
      </div>
      <n-space>
        <n-button @click="loadData">刷新</n-button>
        <n-button type="primary" @click="openCreate()">新增菜单</n-button>
      </n-space>
    </div>

    <n-card :bordered="false" size="small">
      <n-data-table :columns="columns" :data="flatMenus" :loading="loading" :pagination="false" />
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingMenu ? '编辑菜单' : '新增菜单'"
      class="form-modal"
      :style="{ width: 'min(760px, calc(100vw - 32px))' }"
    >
      <n-form :model="formValue" label-placement="top">
        <n-form-item label="上级菜单">
          <n-tree-select v-model:value="formValue.parentId" clearable :options="parentOptions" />
        </n-form-item>
        <n-form-item label="菜单名称">
          <n-input v-model:value="formValue.name" />
        </n-form-item>
        <n-form-item label="菜单标题">
          <n-input v-model:value="formValue.title" />
        </n-form-item>
        <n-form-item label="路由路径">
          <n-input v-model:value="formValue.path" />
        </n-form-item>
        <n-form-item label="组件路径">
          <n-input v-model:value="formValue.component" />
        </n-form-item>
        <n-form-item label="图标">
          <n-input v-model:value="formValue.icon" />
        </n-form-item>
        <n-form-item label="权限标识">
          <n-input v-model:value="formValue.permission" />
        </n-form-item>
        <div class="grid-form">
          <n-form-item label="菜单类型">
            <n-select v-model:value="formValue.menuType" :options="menuTypeOptions" />
          </n-form-item>
          <n-form-item label="排序值">
            <n-input-number v-model:value="formValue.sort" :min="0" />
          </n-form-item>
        </div>
        <n-form-item label="是否可见">
          <n-switch v-model:value="formValue.visible" />
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
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NTreeSelect,
  useMessage
} from 'naive-ui'
import { createMenu, deleteMenu, fetchMenuTree, reorderMenus, updateMenu } from '../api/menu'
import type { MenuFormRecord, MenuRecord } from '../types/rbac'

const message = useMessage()
const loading = ref(false)
const showModal = ref(false)
const menus = ref<MenuRecord[]>([])
const editingMenu = ref<MenuRecord | null>(null)
const formValue = reactive<MenuFormRecord>({
  parentId: null,
  path: '',
  name: '',
  title: '',
  icon: '',
  permission: '',
  component: '',
  menuType: 'MENU',
  visible: true,
  sort: 10
})

const menuTypeOptions = [
  { label: '目录', value: 'CATALOG' },
  { label: '菜单', value: 'MENU' }
]

const parentOptions = computed<TreeSelectOption[]>(() => mapParentOptions(menus.value))

const flatMenus = computed(() => flattenMenus(menus.value))

const columns: DataTableColumns<MenuRecord & { depth: number }> = [
  {
    title: '菜单标题',
    key: 'title',
    render: (row) => h('div', { style: { paddingLeft: `${row.depth * 18}px`, fontWeight: row.depth === 0 ? '600' : '400' } }, row.title)
  },
  { title: '路由路径', key: 'path' },
  { title: '权限标识', key: 'permission' },
  { title: '排序', key: 'sort' },
  {
    title: '状态',
    key: 'visible',
    render: (row) => h(NTag, { type: row.visible ? 'success' : 'warning', bordered: false }, { default: () => (row.visible ? '显示' : '隐藏') })
  },
  {
    title: '操作',
    key: 'actions',
    render: (row) =>
      h(NSpace, { wrap: false }, {
        default: () => [
          h(NButton, { size: 'small', tertiary: true, onClick: () => openCreate(row) }, { default: () => '新增下级' }),
          h(NButton, { size: 'small', tertiary: true, onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(
            NPopconfirm,
            { onPositiveClick: () => handleDelete(row.menuId ?? 0) },
            {
              trigger: () => h(NButton, { size: 'small', tertiary: true, type: 'error' }, { default: () => '删除' }),
              default: () => '确认删除当前菜单吗？'
            }
          )
        ]
      })
  }
]

function flattenMenus(records: MenuRecord[], depth = 0): Array<MenuRecord & { depth: number }> {
  return records.flatMap((item) => [
    { ...item, depth },
    ...flattenMenus(item.children ?? [], depth + 1)
  ])
}

function mapParentOptions(records: MenuRecord[]): TreeSelectOption[] {
  return records.map((item) => ({
    label: item.title,
    key: item.menuId ?? 0,
    value: item.menuId ?? 0,
    children: item.children?.length ? mapParentOptions(item.children) : undefined
  }))
}

function resetForm() {
  formValue.parentId = null
  formValue.path = ''
  formValue.name = ''
  formValue.title = ''
  formValue.icon = ''
  formValue.permission = ''
  formValue.component = ''
  formValue.menuType = 'MENU'
  formValue.visible = true
  formValue.sort = 10
}

function openCreate(parent?: MenuRecord) {
  editingMenu.value = null
  resetForm()
  formValue.parentId = parent?.menuId ?? null
  delete formValue.menuId
  showModal.value = true
}

function openEdit(menu: MenuRecord) {
  editingMenu.value = menu
  formValue.menuId = menu.menuId
  formValue.parentId = menu.parentId ?? null
  formValue.path = menu.path
  formValue.name = menu.name
  formValue.title = menu.title
  formValue.icon = menu.icon ?? ''
  formValue.permission = menu.permission ?? ''
  formValue.component = menu.component ?? ''
  formValue.menuType = menu.menuType ?? 'MENU'
  formValue.visible = menu.visible ?? true
  formValue.sort = menu.sort ?? 10
  showModal.value = true
}

async function loadData() {
  loading.value = true
  try {
    menus.value = await fetchMenuTree()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载菜单数据失败')
  } finally {
    loading.value = false
  }
}

async function submitForm() {
  try {
    if (editingMenu.value) {
      await updateMenu(editingMenu.value.menuId ?? 0, { ...formValue })
      message.success('菜单更新成功')
    } else {
      await createMenu({ ...formValue })
      message.success('菜单创建成功')
    }

    showModal.value = false
    await loadData()
    await syncOrder()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存菜单失败')
  }
}

async function syncOrder() {
  const orders = flatMenus.value.map((item) => ({
    menuId: item.menuId ?? 0,
    sort: item.sort ?? 0
  }))
  await reorderMenus(orders)
}

async function handleDelete(menuId: number) {
  try {
    await deleteMenu(menuId)
    message.success('菜单已删除')
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除菜单失败')
  }
}

onMounted(() => {
  void loadData()
})
</script>

<style scoped>
.page {
  display: grid;
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
  justify-content: flex-end;
}

.grid-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 900px) {
  .page-header,
  .toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .grid-form {
    grid-template-columns: 1fr;
  }
}
</style>
