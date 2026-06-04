<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>模型管理</h2>
        <p>维护模型编码、所属供应商、能力类型和运行参数。</p>
      </div>
      <n-space>
        <n-button @click="loadData">刷新</n-button>
        <n-button type="primary" @click="openCreate">新增模型</n-button>
      </n-space>
    </div>

    <n-card :bordered="false" size="small">
      <div class="toolbar">
        <n-input v-model:value="keyword" clearable placeholder="搜索模型编码、名称或供应商" />
        <n-select v-model:value="providerFilter" clearable :options="providerOptions" placeholder="按供应商筛选" />
        <n-select v-model:value="statusFilter" clearable :options="statusOptions" placeholder="按状态筛选" />
      </div>
      <n-data-table :columns="columns" :data="filteredModels" :loading="loading" :pagination="false" />
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingModel ? '编辑模型' : '新增模型'"
      class="form-modal"
      :style="{ width: 'min(720px, calc(100vw - 32px))' }"
    >
      <n-form :model="formValue" label-placement="top">
        <div class="form-grid">
          <n-form-item label="所属供应商">
            <n-select v-model:value="formValue.providerId" filterable :options="providerOptions" />
          </n-form-item>
          <n-form-item label="能力类型">
            <n-select v-model:value="formValue.capabilityType" :options="capabilityOptions" />
          </n-form-item>
          <n-form-item label="模型编码">
            <n-input v-model:value="formValue.modelCode" placeholder="例如 gpt-4.1 或 claude-3-7-sonnet-latest" />
          </n-form-item>
          <n-form-item label="显示名称">
            <n-input v-model:value="formValue.displayName" placeholder="例如 GPT-4.1" />
          </n-form-item>
          <n-form-item label="上下文窗口">
            <n-input-number v-model:value="formValue.contextWindow" :min="1" clearable placeholder="可选" />
          </n-form-item>
          <n-form-item label="最大输出 Token">
            <n-input-number v-model:value="formValue.maxOutputTokens" :min="1" clearable placeholder="可选" />
          </n-form-item>
          <n-form-item label="排序">
            <n-input-number v-model:value="formValue.sort" :min="0" />
          </n-form-item>
          <n-form-item label="启用状态">
            <n-switch v-model:value="formValue.enabled" />
          </n-form-item>
          <n-form-item label="支持图片输入" path="imageInputSupported">
            <n-switch v-model:value="formValue.imageInputSupported" />
          </n-form-item>
          <n-form-item v-if="formValue.imageInputSupported" label="图片 token 估算" path="imageTokenEstimate">
            <n-input-number
              v-model:value="formValue.imageTokenEstimate"
              :min="1"
              clearable
              placeholder="默认 1500"
            />
          </n-form-item>
        </div>
        <div class="switch-row">
          <n-space item-style="display: flex; align-items: center; gap: 8px;">
            <n-switch v-model:value="formValue.streamSupported" />
            <span>支持流式输出</span>
            <n-switch v-model:value="formValue.reasoningSupported" />
            <span>支持思考内容</span>
            <n-switch v-model:value="formValue.defaultModel" />
            <span>设为默认模型</span>
          </n-space>
        </div>
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
  createAiModel,
  deleteAiModel,
  fetchAiModels,
  fetchAiProviders,
  updateAiModel
} from '../api/ai'
import type { AiModelFormPayload, AiModelRecord, AiProviderRecord } from '../types/ai'

const message = useMessage()
const loading = ref(false)
const showModal = ref(false)
const keyword = ref('')
const providerFilter = ref<number | null>(null)
const statusFilter = ref<string | null>(null)
const models = ref<AiModelRecord[]>([])
const providers = ref<AiProviderRecord[]>([])
const editingModel = ref<AiModelRecord | null>(null)

const capabilityOptions: SelectOption[] = [
  { label: '对话', value: 'CHAT' },
  { label: '推理', value: 'REASONING' },
  { label: '多模态', value: 'MULTIMODAL' },
  { label: 'Embedding', value: 'EMBEDDING' }
]

const statusOptions: SelectOption[] = [
  { label: '启用', value: 'true' },
  { label: '停用', value: 'false' }
]

const capabilityLabelMap = new Map(capabilityOptions.map((item) => [String(item.value), String(item.label)]))

const formValue = reactive<AiModelFormPayload>({
  providerId: null,
  modelCode: '',
  displayName: '',
  capabilityType: 'CHAT',
  streamSupported: true,
  reasoningSupported: false,
  imageInputSupported: false,
  imageTokenEstimate: null,
  contextWindow: null,
  maxOutputTokens: null,
  defaultModel: false,
  enabled: true,
  sort: 0
})

const providerOptions = computed<SelectOption[]>(() =>
  providers.value.map((item) => ({
    label: `${item.name}（${item.code}）`,
    value: item.providerId
  }))
)

const filteredModels = computed(() =>
  models.value.filter((item) => {
    const matchedKeyword =
      !keyword.value ||
      item.modelCode.includes(keyword.value) ||
      item.displayName.includes(keyword.value) ||
      item.providerName.includes(keyword.value) ||
      item.providerCode.includes(keyword.value)
    const matchedProvider = providerFilter.value === null || item.providerId === providerFilter.value
    const matchedStatus = statusFilter.value === null || String(item.enabled) === statusFilter.value
    return matchedKeyword && matchedProvider && matchedStatus
  })
)

const columns: DataTableColumns<AiModelRecord> = [
  { title: '模型编码', key: 'modelCode', width: 220, ellipsis: { tooltip: true } },
  { title: '显示名称', key: 'displayName', width: 180 },
  { title: '供应商', key: 'providerName', width: 160 },
  {
    title: '能力',
    key: 'capabilityType',
    width: 100,
    render: (row) => capabilityLabelMap.get(row.capabilityType) ?? row.capabilityType
  },
  {
    title: '特性',
    key: 'features',
    width: 230,
    render: (row) =>
      h(NSpace, { wrap: false, size: 6 }, {
        default: () => [
          row.streamSupported
            ? h(NTag, { type: 'info', bordered: false }, { default: () => '流式' })
            : null,
          row.reasoningSupported
            ? h(NTag, { type: 'success', bordered: false }, { default: () => '思考' })
            : null,
          row.imageInputSupported
            ? h(NTag, { type: 'primary', bordered: false }, { default: () => '图片' })
            : null,
          row.defaultModel
            ? h(NTag, { type: 'warning', bordered: false }, { default: () => '默认' })
            : null
        ].filter(Boolean)
      })
  },
  {
    title: '上下文',
    key: 'contextWindow',
    width: 110,
    render: (row) => row.contextWindow ?? '-'
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
    title: '操作',
    key: 'actions',
    width: 230,
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
            { onPositiveClick: () => handleDelete(row.modelId) },
            {
              trigger: () => h(NButton, { size: 'small', tertiary: true, type: 'error' }, { default: () => '删除' }),
              default: () => '确认删除当前模型吗？'
            }
          )
        ]
      })
  }
]

function resetForm() {
  formValue.providerId = providers.value[0]?.providerId ?? null
  formValue.modelCode = ''
  formValue.displayName = ''
  formValue.capabilityType = 'CHAT'
  formValue.streamSupported = true
  formValue.reasoningSupported = false
  formValue.imageInputSupported = false
  formValue.imageTokenEstimate = null
  formValue.contextWindow = null
  formValue.maxOutputTokens = null
  formValue.defaultModel = false
  formValue.enabled = true
  formValue.sort = 0
}

function openCreate() {
  editingModel.value = null
  resetForm()
  showModal.value = true
}

function openEdit(model: AiModelRecord) {
  editingModel.value = model
  formValue.providerId = model.providerId
  formValue.modelCode = model.modelCode
  formValue.displayName = model.displayName
  formValue.capabilityType = model.capabilityType
  formValue.streamSupported = model.streamSupported
  formValue.reasoningSupported = model.reasoningSupported
  formValue.imageInputSupported = model.imageInputSupported
  formValue.imageTokenEstimate = model.imageTokenEstimate ?? null
  formValue.contextWindow = model.contextWindow ?? null
  formValue.maxOutputTokens = model.maxOutputTokens ?? null
  formValue.defaultModel = model.defaultModel
  formValue.enabled = model.enabled
  formValue.sort = model.sort
  showModal.value = true
}

async function loadData() {
  loading.value = true
  try {
    const [modelData, providerData] = await Promise.all([fetchAiModels(), fetchAiProviders()])
    models.value = modelData
    providers.value = providerData
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载模型数据失败')
  } finally {
    loading.value = false
  }
}

async function submitForm() {
  if (!formValue.providerId) {
    message.error('请选择供应商')
    return
  }

  try {
    const payload: AiModelFormPayload = {
      providerId: formValue.providerId,
      modelCode: formValue.modelCode,
      displayName: formValue.displayName,
      capabilityType: formValue.capabilityType,
      streamSupported: formValue.streamSupported,
      reasoningSupported: formValue.reasoningSupported,
      imageInputSupported: formValue.imageInputSupported,
      imageTokenEstimate: formValue.imageInputSupported ? formValue.imageTokenEstimate : null,
      contextWindow: formValue.contextWindow,
      maxOutputTokens: formValue.maxOutputTokens,
      defaultModel: formValue.defaultModel,
      enabled: formValue.enabled,
      sort: formValue.sort
    }
    if (editingModel.value) {
      await updateAiModel(editingModel.value.modelId, payload)
      message.success('模型更新成功')
    } else {
      await createAiModel(payload)
      message.success('模型创建成功')
    }

    showModal.value = false
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存模型失败')
  }
}

async function handleStatusChange(model: AiModelRecord, enabled: boolean) {
  try {
    await updateAiModel(model.modelId, {
      providerId: model.providerId,
      modelCode: model.modelCode,
      displayName: model.displayName,
      capabilityType: model.capabilityType,
      streamSupported: model.streamSupported,
      reasoningSupported: model.reasoningSupported,
      imageInputSupported: model.imageInputSupported,
      imageTokenEstimate: model.imageInputSupported ? model.imageTokenEstimate ?? null : null,
      contextWindow: model.contextWindow ?? null,
      maxOutputTokens: model.maxOutputTokens ?? null,
      defaultModel: model.defaultModel,
      enabled,
      sort: model.sort
    })
    model.enabled = enabled
    message.success('模型状态已更新')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '更新模型状态失败')
  }
}

async function handleDelete(modelId: number) {
  try {
    await deleteAiModel(modelId)
    message.success('模型已删除')
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除模型失败')
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

.switch-row {
  padding: 4px 0 12px;
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
