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
      <n-data-table
        :columns="columns"
        :data="tableData"
        :loading="loading"
        :pagination="false"
        :bordered="false"
        :single-line="false"
        :scroll-x="1160"
        children-key="children"
        :row-key="(row: MenuRecord) => row.menuId ?? row.name"
      />
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingMenu ? '编辑菜单' : '新增菜单'"
      class="form-modal"
      :style="{ width: 'min(820px, calc(100vw - 32px))' }"
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
        <div class="grid-form">
          <n-form-item label="路由路径">
            <n-input v-model:value="formValue.path" />
          </n-form-item>
          <n-form-item label="组件路径">
            <n-input v-model:value="formValue.component" />
          </n-form-item>
        </div>
        <div class="grid-form">
          <n-form-item label="图标">
            <n-input v-model:value="formValue.icon" />
          </n-form-item>
          <n-form-item label="权限标识">
            <n-input v-model:value="formValue.permission" />
          </n-form-item>
        </div>
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
import type { DataTableColumns } from 'naive-ui'
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
import { buildMenuParentOptions, buildMenuTableData } from './system-menu.helpers'

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

const parentOptions = computed(() => buildMenuParentOptions(menus.value))
const tableData = computed(() => buildMenuTableData(menus.value))

const columns: DataTableColumns<MenuRecord> = [
  {
    title: '菜单标题',
    key: 'title',
    minWidth: 240,
    ellipsis: { tooltip: true },
    render: (row) => h('span', { class: 'menu-title-cell' }, row.title)
  },
  {
    title: '路由路径',
    key: 'path',
    minWidth: 180,
    ellipsis: { tooltip: true }
  },
  {
    title: '组件路径',
    key: 'component',
    minWidth: 190,
    ellipsis: { tooltip: true }
  },
  {
    title: '权限标识',
    key: 'permission',
    minWidth: 180,
    ellipsis: { tooltip: true }
  },
  {
    title: '排序',
    key: 'sort',
    width: 92
  },
  {
    title: '状态',
    key: 'visible',
    width: 110,
    render: (row) =>
      h(
        NTag,
        { type: row.visible ? 'success' : 'warning', bordered: false },
        { default: () => (row.visible ? '显示' : '隐藏') }
      )
  },
  {
    title: '操作',
    key: 'actions',
    width: 220,
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
  const orders = flattenForOrder(tableData.value).map((item) => ({
    menuId: item.menuId ?? 0,
    sort: item.sort ?? 0
  }))
  await reorderMenus(orders)
}

function flattenForOrder(records: MenuRecord[]): MenuRecord[] {
  return records.flatMap((item) => [item, ...flattenForOrder(item.children ?? [])])
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

.grid-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.menu-title-cell {
  font-weight: 600;
}

:deep(.n-card) {
  flex: 1;
}

:deep(.n-card__content) {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

:deep(.n-data-table) {
  flex: 1;
}

:deep(.n-data-table-wrapper) {
  min-width: 0;
}

:deep(.n-data-table-base-table-body) td,
:deep(.n-data-table-base-table-header) th {
  vertical-align: middle;
}

@media (max-width: 900px) {
  .page-header {
    flex-direction: column;
    align-items: stretch;
  }

  .grid-form {
    grid-template-columns: 1fr;
  }
}
</style>
