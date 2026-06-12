<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>MCP 服务</h2>
        <p>维护外部 MCP Server、同步工具并控制启停状态。</p>
      </div>
      <n-space>
        <n-button @click="loadData">刷新</n-button>
        <n-button type="primary" @click="openCreate">新增服务</n-button>
      </n-space>
    </div>

    <n-card :bordered="false" size="small">
      <div class="toolbar">
        <n-input v-model:value="keyword" clearable placeholder="搜索标识、名称或地址" />
        <n-select v-model:value="statusFilter" clearable :options="statusOptions" placeholder="按状态筛选" />
        <n-select v-model:value="syncStatusFilter" clearable :options="syncStatusOptions" placeholder="按同步状态筛选" />
      </div>
      <n-data-table
        :columns="columns"
        :data="filteredServers"
        :loading="loading"
        :pagination="{ pageSize: 10 }"
        :scroll-x="1380"
      />
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingServer ? '编辑 MCP 服务' : '新增 MCP 服务'"
      class="form-modal"
      :style="{ width: 'min(820px, calc(100vw - 32px))' }"
    >
      <n-form :model="formValue" label-placement="top">
        <div class="form-grid">
          <n-form-item label="服务标识">
            <n-input v-model:value="formValue.serverKey" :disabled="Boolean(editingServer)" placeholder="例如 github" />
          </n-form-item>
          <n-form-item label="服务名称">
            <n-input v-model:value="formValue.name" placeholder="例如 GitHub MCP" />
          </n-form-item>
          <n-form-item label="传输类型">
            <n-select v-model:value="formValue.transportType" :options="transportTypeOptions" />
          </n-form-item>
          <n-form-item label="协议版本">
            <n-input v-model:value="formValue.protocolVersion" placeholder="例如 2025-11-25" />
          </n-form-item>
          <n-form-item label="认证方式">
            <n-select v-model:value="formValue.authType" :options="authTypeOptions" />
          </n-form-item>
          <n-form-item label="认证 Token">
            <n-input
              v-model:value="formValue.authToken"
              type="password"
              show-password-on="click"
              :placeholder="editingServer?.hasAuthToken ? '留空则保留已配置 Token' : '可选'"
            />
          </n-form-item>
          <n-form-item label="连接超时">
            <n-input-number v-model:value="formValue.connectTimeoutSeconds" :min="1" :max="600" />
          </n-form-item>
          <n-form-item label="请求超时">
            <n-input-number v-model:value="formValue.requestTimeoutSeconds" :min="1" :max="600" />
          </n-form-item>
          <n-form-item label="启用状态">
            <n-switch v-model:value="formValue.enabled" />
          </n-form-item>
          <n-form-item label="排序">
            <n-input-number v-model:value="formValue.sort" :min="0" />
          </n-form-item>
        </div>

        <n-form-item label="服务地址">
          <n-input v-model:value="formValue.endpointUrl" placeholder="例如 https://mcp.example.com/mcp" />
        </n-form-item>
        <n-form-item label="描述">
          <n-input v-model:value="formValue.description" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
        </n-form-item>
        <n-form-item label="扩展请求头">
          <n-input
            v-model:value="formValue.extraHeaders"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 5 }"
            placeholder='JSON 格式，例如 {"apiKeyHeaderName":"X-API-Key"}'
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showModal = false">取消</n-button>
          <n-button type="primary" @click="submitForm">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <n-modal
      v-model:show="showToolModal"
      preset="card"
      :title="selectedServer ? `${selectedServer.name} 工具列表` : '工具列表'"
      class="tool-modal"
      :style="{ width: 'min(980px, calc(100vw - 32px))' }"
    >
      <n-data-table
        :columns="toolColumns"
        :data="tools"
        :loading="toolsLoading"
        :pagination="{ pageSize: 8 }"
        :scroll-x="960"
      />
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
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  useMessage
} from 'naive-ui'
import {
  createMcpServer,
  deleteMcpServer,
  fetchMcpServers,
  fetchMcpTools,
  syncMcpServerTools,
  testMcpServer,
  updateMcpServer,
  updateMcpToolEnabled
} from '../api/mcp'
import type { McpServerFormPayload, McpServerRecord, McpToolRecord } from '../types/mcp'

const message = useMessage()
const loading = ref(false)
const toolsLoading = ref(false)
const showModal = ref(false)
const showToolModal = ref(false)
const keyword = ref('')
const statusFilter = ref<string | null>(null)
const syncStatusFilter = ref<string | null>(null)
const servers = ref<McpServerRecord[]>([])
const tools = ref<McpToolRecord[]>([])
const editingServer = ref<McpServerRecord | null>(null)
const selectedServer = ref<McpServerRecord | null>(null)
const testingServerIds = ref<Set<number>>(new Set())
const syncingServerIds = ref<Set<number>>(new Set())

const transportTypeOptions: SelectOption[] = [
  { label: 'Streamable HTTP', value: 'STREAMABLE_HTTP' }
]

const authTypeOptions: SelectOption[] = [
  { label: '无需认证', value: 'NONE' },
  { label: 'Bearer Token', value: 'BEARER' },
  { label: 'API Key Header', value: 'API_KEY_HEADER' }
]

const statusOptions: SelectOption[] = [
  { label: '启用', value: 'true' },
  { label: '停用', value: 'false' }
]

const syncStatusOptions: SelectOption[] = [
  { label: '同步成功', value: 'SUCCESS' },
  { label: '同步失败', value: 'FAILED' },
  { label: '未同步', value: 'NONE' }
]

const syncStatusLabelMap = new Map(syncStatusOptions.map((item) => [String(item.value), String(item.label)]))

const formValue = reactive<McpServerFormPayload>({
  serverKey: '',
  name: '',
  description: '',
  endpointUrl: '',
  transportType: 'STREAMABLE_HTTP',
  protocolVersion: '2025-11-25',
  authType: 'NONE',
  authToken: '',
  extraHeaders: '{}',
  connectTimeoutSeconds: 10,
  requestTimeoutSeconds: 60,
  enabled: true,
  sort: 0
})

const filteredServers = computed(() =>
  servers.value.filter((item) => {
    const matchedKeyword =
      !keyword.value ||
      item.serverKey.includes(keyword.value) ||
      item.name.includes(keyword.value) ||
      item.endpointUrl.includes(keyword.value)
    const matchedStatus = statusFilter.value === null || String(item.enabled) === statusFilter.value
    const syncValue = item.lastSyncStatus ?? 'NONE'
    const matchedSyncStatus = syncStatusFilter.value === null || syncValue === syncStatusFilter.value
    return matchedKeyword && matchedStatus && matchedSyncStatus
  })
)

const columns: DataTableColumns<McpServerRecord> = [
  { title: '标识', key: 'serverKey', width: 140 },
  { title: '名称', key: 'name', width: 160 },
  { title: '地址', key: 'endpointUrl', ellipsis: { tooltip: true } },
  {
    title: '认证',
    key: 'authType',
    width: 150,
    render: (row) => row.authType === 'NONE' ? '无' : row.authType
  },
  {
    title: 'Token',
    key: 'hasAuthToken',
    width: 90,
    render: (row) =>
      h(NTag, { type: row.hasAuthToken ? 'success' : 'default', bordered: false }, {
        default: () => (row.hasAuthToken ? '已配置' : '未配置')
      })
  },
  {
    title: '状态',
    key: 'enabled',
    width: 90,
    render: (row) =>
      h(NTag, { type: row.enabled ? 'success' : 'warning', bordered: false }, {
        default: () => (row.enabled ? '启用' : '停用')
      })
  },
  {
    title: '同步',
    key: 'lastSyncStatus',
    width: 120,
    render: (row) =>
      h(NTag, { type: syncStatusTagType(row.lastSyncStatus), bordered: false }, {
        default: () => syncStatusLabel(row.lastSyncStatus)
      })
  },
  { title: '工具数', key: 'toolCount', width: 90 },
  { title: '排序', key: 'sort', width: 80 },
  {
    title: '操作',
    key: 'actions',
    width: 420,
    fixed: 'right',
    render: (row) =>
      h(NSpace, { wrap: false }, {
        default: () => [
          h(
            NButton,
            {
              size: 'small',
              tertiary: true,
              loading: testingServerIds.value.has(row.serverId),
              disabled: testingServerIds.value.has(row.serverId),
              onClick: () => handleTest(row)
            },
            { default: () => '测试' }
          ),
          h(
            NButton,
            {
              size: 'small',
              tertiary: true,
              loading: syncingServerIds.value.has(row.serverId),
              disabled: syncingServerIds.value.has(row.serverId),
              onClick: () => handleSync(row)
            },
            { default: () => '同步工具' }
          ),
          h(NButton, { size: 'small', tertiary: true, onClick: () => openTools(row) }, {
            default: () => '工具列表'
          }),
          h(NButton, { size: 'small', tertiary: true, onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(
            NPopconfirm,
            { onPositiveClick: () => handleDelete(row.serverId) },
            {
              trigger: () => h(NButton, { size: 'small', tertiary: true, type: 'error' }, { default: () => '删除' }),
              default: () => '确认删除当前 MCP 服务吗？'
            }
          )
        ]
      })
  }
]

const toolColumns: DataTableColumns<McpToolRecord> = [
  { title: '工具名', key: 'toolName', width: 190 },
  { title: '暴露名称', key: 'exposedToolName', width: 260, ellipsis: { tooltip: true } },
  { title: '描述', key: 'description', ellipsis: { tooltip: true } },
  {
    title: '启用',
    key: 'enabled',
    width: 90,
    render: (row) =>
      h(NSwitch, {
        value: row.enabled,
        size: 'small',
        onUpdateValue: (value: boolean) => handleToolEnabled(row, value)
      })
  },
  {
    title: '最近发现',
    key: 'lastSeenAt',
    width: 180,
    render: (row) => formatDate(row.lastSeenAt)
  }
]

function resetForm() {
  formValue.serverKey = ''
  formValue.name = ''
  formValue.description = ''
  formValue.endpointUrl = ''
  formValue.transportType = 'STREAMABLE_HTTP'
  formValue.protocolVersion = '2025-11-25'
  formValue.authType = 'NONE'
  formValue.authToken = ''
  formValue.extraHeaders = '{}'
  formValue.connectTimeoutSeconds = 10
  formValue.requestTimeoutSeconds = 60
  formValue.enabled = true
  formValue.sort = 0
}

function openCreate() {
  editingServer.value = null
  resetForm()
  showModal.value = true
}

function openEdit(server: McpServerRecord) {
  editingServer.value = server
  formValue.serverKey = server.serverKey
  formValue.name = server.name
  formValue.description = server.description ?? ''
  formValue.endpointUrl = server.endpointUrl
  formValue.transportType = server.transportType
  formValue.protocolVersion = server.protocolVersion
  formValue.authType = server.authType
  formValue.authToken = ''
  formValue.extraHeaders = server.extraHeaders ?? '{}'
  formValue.connectTimeoutSeconds = server.connectTimeoutSeconds
  formValue.requestTimeoutSeconds = server.requestTimeoutSeconds
  formValue.enabled = server.enabled
  formValue.sort = server.sort
  showModal.value = true
}

async function loadData() {
  loading.value = true
  try {
    servers.value = await fetchMcpServers()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载 MCP 服务失败')
  } finally {
    loading.value = false
  }
}

async function submitForm() {
  try {
    if (editingServer.value) {
      await updateMcpServer(editingServer.value.serverId, buildUpdatePayload())
      message.success('MCP 服务更新成功')
    } else {
      await createMcpServer({ ...formValue })
      message.success('MCP 服务创建成功')
    }
    showModal.value = false
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存 MCP 服务失败')
  }
}

async function handleTest(row: McpServerRecord) {
  testingServerIds.value = new Set(testingServerIds.value).add(row.serverId)
  try {
    const result = await testMcpServer(row.serverId)
    result.success ? message.success(result.message) : message.error(result.message)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '测试 MCP 服务失败')
  } finally {
    const next = new Set(testingServerIds.value)
    next.delete(row.serverId)
    testingServerIds.value = next
  }
}

async function handleSync(row: McpServerRecord) {
  syncingServerIds.value = new Set(syncingServerIds.value).add(row.serverId)
  try {
    const result = await syncMcpServerTools(row.serverId)
    result.success ? message.success(result.message) : message.error(result.message)
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '同步 MCP 工具失败')
  } finally {
    const next = new Set(syncingServerIds.value)
    next.delete(row.serverId)
    syncingServerIds.value = next
  }
}

async function openTools(row: McpServerRecord) {
  selectedServer.value = row
  showToolModal.value = true
  toolsLoading.value = true
  try {
    tools.value = await fetchMcpTools(row.serverId)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载 MCP 工具失败')
  } finally {
    toolsLoading.value = false
  }
}

async function handleToolEnabled(row: McpToolRecord, enabled: boolean) {
  try {
    const updated = await updateMcpToolEnabled(row.toolId, enabled)
    tools.value = tools.value.map((item) => (item.toolId === row.toolId ? updated : item))
    message.success('MCP 工具状态已更新')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '更新 MCP 工具状态失败')
  }
}

async function handleDelete(serverId: number) {
  try {
    await deleteMcpServer(serverId)
    message.success('MCP 服务已删除')
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除 MCP 服务失败')
  }
}

function buildUpdatePayload(): Omit<McpServerFormPayload, 'serverKey'> {
  return {
    name: formValue.name,
    description: formValue.description,
    endpointUrl: formValue.endpointUrl,
    transportType: formValue.transportType,
    protocolVersion: formValue.protocolVersion,
    authType: formValue.authType,
    authToken: formValue.authToken,
    extraHeaders: formValue.extraHeaders,
    connectTimeoutSeconds: formValue.connectTimeoutSeconds,
    requestTimeoutSeconds: formValue.requestTimeoutSeconds,
    enabled: formValue.enabled,
    sort: formValue.sort
  }
}

function syncStatusLabel(status?: string | null) {
  return syncStatusLabelMap.get(status ?? 'NONE') ?? status ?? '未同步'
}

function syncStatusTagType(status?: string | null) {
  if (status === 'SUCCESS') {
    return 'success'
  }
  if (status === 'FAILED') {
    return 'error'
  }
  return 'default'
}

function formatDate(value?: string | null) {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-CN')
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
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
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
  .page-header {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar,
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
