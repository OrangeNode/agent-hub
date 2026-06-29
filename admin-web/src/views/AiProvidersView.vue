<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>供应商管理</h2>
        <p>维护模型服务连接、协议类型、认证方式和启停状态。</p>
      </div>
      <n-space>
        <n-button @click="loadData">刷新</n-button>
        <n-button type="primary" @click="openCreate">新增供应商</n-button>
      </n-space>
    </div>

    <n-card :bordered="false" size="small">
      <div class="toolbar">
        <n-input v-model:value="keyword" clearable placeholder="搜索编码、名称或基础地址" />
        <n-select v-model:value="protocolFilter" clearable :options="protocolOptions" placeholder="按协议筛选" />
        <n-select v-model:value="statusFilter" clearable :options="statusOptions" placeholder="按状态筛选" />
      </div>
      <n-data-table
        :columns="columns"
        :data="filteredProviders"
        :loading="loading"
        :pagination="false"
        :scroll-x="1120"
      />
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingProvider ? '编辑供应商' : '新增供应商'"
      class="form-modal"
      :style="{ width: 'min(760px, calc(100vw - 32px))' }"
    >
      <n-form :model="formValue" label-placement="top">
        <div class="form-grid">
          <n-form-item label="供应商编码">
            <n-input v-model:value="formValue.code" :disabled="Boolean(editingProvider)" placeholder="例如 openai" />
          </n-form-item>
          <n-form-item label="供应商名称">
            <n-input v-model:value="formValue.name" placeholder="例如 OpenAI" />
          </n-form-item>
          <n-form-item label="供应商类型">
            <n-select v-model:value="formValue.providerType" :options="providerTypeOptions" />
          </n-form-item>
          <n-form-item label="协议类型">
            <n-select v-model:value="formValue.protocolType" :options="protocolOptions" />
          </n-form-item>
          <n-form-item label="认证方式">
            <n-select v-model:value="formValue.authType" :options="authTypeOptions" />
          </n-form-item>
          <n-form-item label="API 版本">
            <n-input v-model:value="formValue.apiVersion" placeholder="可选，例如 2023-06-01" />
          </n-form-item>
        </div>
        <n-form-item label="基础地址">
          <n-input v-model:value="formValue.baseUrl" placeholder="例如 https://api.openai.com/v1" />
        </n-form-item>
        <n-form-item label="API Key">
          <n-input
            v-model:value="formValue.apiKey"
            type="password"
            show-password-on="click"
            :placeholder="editingProvider?.hasApiKey ? '留空则保留已配置密钥' : '请输入 API Key'"
          />
        </n-form-item>
        <div class="form-grid">
          <n-form-item label="启用状态">
            <n-switch v-model:value="formValue.enabled" />
          </n-form-item>
          <n-form-item label="排序">
            <n-input-number v-model:value="formValue.sort" :min="0" />
          </n-form-item>
        </div>
        <n-form-item label="扩展请求头">
          <n-input
            v-model:value="formValue.extraHeaders"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 5 }"
            placeholder='JSON 格式，例如 {"OpenAI-Beta":"assistants=v2"}'
          />
        </n-form-item>
        <n-form-item label="扩展请求体">
          <n-input
            v-model:value="formValue.extraBody"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 5 }"
            placeholder='JSON 格式，例如 {"temperature":0.7}'
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
  createAiProvider,
  deleteAiProvider,
  fetchAiProviders,
  testAiProviderConnection,
  updateAiProvider
} from '../api/ai'
import type { AiProviderFormPayload, AiProviderRecord } from '../types/ai'

const message = useMessage()
const loading = ref(false)
const showModal = ref(false)
const keyword = ref('')
const protocolFilter = ref<string | null>(null)
const statusFilter = ref<string | null>(null)
const providers = ref<AiProviderRecord[]>([])
const editingProvider = ref<AiProviderRecord | null>(null)
const testingProviderIds = ref<Set<number>>(new Set())

const providerTypeOptions: SelectOption[] = [
  { label: 'LLM', value: 'LLM' }
]

const protocolOptions: SelectOption[] = [
  { label: 'OpenAI 兼容协议', value: 'OPENAI_COMPATIBLE' }
]

const authTypeOptions: SelectOption[] = [
  { label: 'Bearer Token', value: 'BEARER' },
  { label: 'API Key', value: 'API_KEY' }
]

const statusOptions: SelectOption[] = [
  { label: '启用', value: 'true' },
  { label: '停用', value: 'false' }
]

const protocolLabelMap = new Map(protocolOptions.map((item) => [String(item.value), String(item.label)]))

const formValue = reactive<AiProviderFormPayload>({
  code: '',
  name: '',
  providerType: 'LLM',
  protocolType: 'OPENAI_COMPATIBLE',
  baseUrl: '',
  apiKey: '',
  authType: 'BEARER',
  apiVersion: '',
  extraHeaders: '',
  extraBody: '',
  enabled: true,
  sort: 0
})

const filteredProviders = computed(() =>
  providers.value.filter((item) => {
    const matchedKeyword =
      !keyword.value ||
      item.code.includes(keyword.value) ||
      item.name.includes(keyword.value) ||
      item.baseUrl.includes(keyword.value)
    const matchedProtocol = !protocolFilter.value || item.protocolType === protocolFilter.value
    const matchedStatus = statusFilter.value === null || String(item.enabled) === statusFilter.value
    return matchedKeyword && matchedProtocol && matchedStatus
  })
)

const columns: DataTableColumns<AiProviderRecord> = [
  { title: '编码', key: 'code', width: 140 },
  { title: '名称', key: 'name', width: 160 },
  {
    title: '协议',
    key: 'protocolType',
    width: 210,
    render: (row) => protocolLabelMap.get(row.protocolType) ?? row.protocolType
  },
  { title: '基础地址', key: 'baseUrl', ellipsis: { tooltip: true } },
  {
    title: '密钥',
    key: 'hasApiKey',
    width: 90,
    render: (row) =>
      h(NTag, { type: row.hasApiKey ? 'success' : 'warning', bordered: false }, {
        default: () => (row.hasApiKey ? '已配置' : '未配置')
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
  { title: '排序', key: 'sort', width: 80 },
  {
    title: '操作',
    key: 'actions',
    width: 280,
    fixed: 'right',
    render: (row) =>
      h(NSpace, { wrap: false }, {
        default: () => [
          h(NButton, { size: 'small', tertiary: true, onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(
            NButton,
            {
              size: 'small',
              tertiary: true,
              loading: testingProviderIds.value.has(row.providerId),
              disabled: testingProviderIds.value.has(row.providerId),
              onClick: () => handleTestConnection(row)
            },
            { default: () => '测试' }
          ),
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
            { onPositiveClick: () => handleDelete(row.providerId) },
            {
              trigger: () => h(NButton, { size: 'small', tertiary: true, type: 'error' }, { default: () => '删除' }),
              default: () => '确认删除当前供应商吗？'
            }
          )
        ]
      })
  }
]

function resetForm() {
  formValue.code = ''
  formValue.name = ''
  formValue.providerType = 'LLM'
  formValue.protocolType = 'OPENAI_COMPATIBLE'
  formValue.baseUrl = ''
  formValue.apiKey = ''
  formValue.authType = 'BEARER'
  formValue.apiVersion = ''
  formValue.extraHeaders = ''
  formValue.extraBody = ''
  formValue.enabled = true
  formValue.sort = 0
}

function openCreate() {
  editingProvider.value = null
  resetForm()
  showModal.value = true
}

function openEdit(provider: AiProviderRecord) {
  editingProvider.value = provider
  formValue.code = provider.code
  formValue.name = provider.name
  formValue.providerType = provider.providerType
  formValue.protocolType = provider.protocolType
  formValue.baseUrl = provider.baseUrl
  formValue.apiKey = ''
  formValue.authType = provider.authType
  formValue.apiVersion = provider.apiVersion ?? ''
  formValue.extraHeaders = provider.extraHeaders ?? ''
  formValue.extraBody = provider.extraBody ?? ''
  formValue.enabled = provider.enabled
  formValue.sort = provider.sort
  showModal.value = true
}

async function loadData() {
  loading.value = true
  try {
    providers.value = await fetchAiProviders()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载供应商数据失败')
  } finally {
    loading.value = false
  }
}

async function submitForm() {
  try {
    if (editingProvider.value) {
      await updateAiProvider(editingProvider.value.providerId, {
        name: formValue.name,
        providerType: formValue.providerType,
        protocolType: formValue.protocolType,
        baseUrl: formValue.baseUrl,
        apiKey: formValue.apiKey,
        authType: formValue.authType,
        apiVersion: formValue.apiVersion,
        extraHeaders: formValue.extraHeaders,
        extraBody: formValue.extraBody,
        enabled: formValue.enabled,
        sort: formValue.sort
      })
      message.success('供应商更新成功')
    } else {
      await createAiProvider({ ...formValue })
      message.success('供应商创建成功')
    }

    showModal.value = false
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存供应商失败')
  }
}

async function handleStatusChange(provider: AiProviderRecord, enabled: boolean) {
  try {
    await updateAiProvider(provider.providerId, {
      name: provider.name,
      providerType: provider.providerType,
      protocolType: provider.protocolType,
      baseUrl: provider.baseUrl,
      apiKey: '',
      authType: provider.authType,
      apiVersion: provider.apiVersion ?? '',
      extraHeaders: provider.extraHeaders ?? '',
      extraBody: provider.extraBody ?? '',
      enabled,
      sort: provider.sort
    })
    provider.enabled = enabled
    message.success('供应商状态已更新')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '更新供应商状态失败')
  }
}

async function handleTestConnection(provider: AiProviderRecord) {
  setProviderTesting(provider.providerId, true)
  try {
    const result = await testAiProviderConnection(provider.providerId)
    const text = `${provider.name}：${result.message}`
    if (result.success) {
      message.success(text)
    } else {
      message.error(text)
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '测试连通性失败')
  } finally {
    setProviderTesting(provider.providerId, false)
  }
}

async function handleDelete(providerId: number) {
  try {
    await deleteAiProvider(providerId)
    message.success('供应商已删除')
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除供应商失败')
  }
}

function setProviderTesting(providerId: number, testing: boolean) {
  const next = new Set(testingProviderIds.value)
  if (testing) {
    next.add(providerId)
  } else {
    next.delete(providerId)
  }
  testingProviderIds.value = next
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
