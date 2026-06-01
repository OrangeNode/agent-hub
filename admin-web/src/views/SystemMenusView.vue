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
          <n-tree-select
            v-model:value="formValue.parentId"
            clearable
            :options="parentOptions"
            placeholder="请选择上级菜单，不选则为一级菜单"
          />
        </n-form-item>
        <n-form-item label="菜单名称">
          <n-input v-model:value="formValue.name" placeholder="请输入唯一名称，例如 systemUsers" />
        </n-form-item>
        <n-form-item label="菜单标题">
          <n-input v-model:value="formValue.title" placeholder="请输入展示标题，例如 用户管理" />
        </n-form-item>
        <div class="grid-form">
          <n-form-item label="路由路径">
            <n-input v-model:value="formValue.path" placeholder="请输入路由路径，例如 /system/users" />
          </n-form-item>
          <n-form-item label="组件路径">
            <n-input
              v-model:value="formValue.component"
              placeholder="请输入组件路径，例如 system/users/index"
            />
          </n-form-item>
        </div>
        <div class="grid-form">
          <n-form-item label="图标选择">
            <div class="icon-field">
              <n-select
                v-model:value="formValue.icon"
                clearable
                filterable
                :options="iconSelectOptions"
                placeholder="请选择内置图标，例如 UserOutlined"
              />
              <n-input
                v-model:value="formValue.icon"
                clearable
                placeholder="也可以直接输入图标名，例如 ApiOutlined"
              />
              <div class="icon-preview">
                <template v-if="selectedIconComponent">
                  <n-icon size="18">
                    <component :is="selectedIconComponent" />
                  </n-icon>
                  <span>{{ formValue.icon }}</span>
                </template>
                <span v-else-if="formValue.icon" class="icon-preview__text">
                  当前图标名：{{ formValue.icon }}（未在内置库中匹配到预览）
                </span>
                <span v-else class="icon-preview__text">请选择或输入图标名称</span>
              </div>
            </div>
          </n-form-item>
          <n-form-item label="权限标识">
            <n-input
              v-model:value="formValue.permission"
              placeholder="请输入权限标识，例如 system:user:list"
            />
          </n-form-item>
        </div>
        <div class="grid-form">
          <n-form-item label="菜单类型">
            <n-select
              v-model:value="formValue.menuType"
              :options="menuTypeOptions"
              placeholder="请选择菜单类型"
            />
          </n-form-item>
          <n-form-item label="排序值">
            <n-input-number v-model:value="formValue.sort" :min="0" placeholder="例如 10" />
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
import type { DataTableColumns, SelectOption } from 'naive-ui'
import {
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NIcon,
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
import { buildMenuIconOptions, resolveMenuIcon } from './menu-icon-registry'

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

const menuTypeOptions: SelectOption[] = [
  { label: '目录', value: 'DIRECTORY' },
  { label: '菜单', value: 'MENU' }
]

const iconSelectOptions = computed<SelectOption[]>(() =>
  buildMenuIconOptions().map((item) => ({
    label: item.label,
    value: item.value
  }))
)

const parentOptions = computed(() => buildMenuParentOptions(menus.value))
const tableData = computed(() => buildMenuTableData(menus.value))
const selectedIconComponent = computed(() => resolveMenuIcon(formValue.icon))

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

.icon-field {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.icon-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 38px;
  padding: 0 12px;
  border: 1px dashed #cbd5e1;
  border-radius: 10px;
  color: #475569;
  background: #f8fafc;
}

.icon-preview__text {
  font-size: 13px;
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
