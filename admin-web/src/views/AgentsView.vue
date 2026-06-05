<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>Agent 列表</h2>
        <p>维护 Agent 标识、系统提示词、默认模型和启停状态。</p>
      </div>
      <n-space>
        <n-button @click="loadData">刷新</n-button>
        <n-button type="primary" @click="openCreate">新增 Agent</n-button>
      </n-space>
    </div>

    <n-card :bordered="false" size="small">
      <div class="toolbar">
        <n-input v-model:value="keyword" clearable placeholder="搜索名称、标识或描述" />
        <n-select v-model:value="statusFilter" clearable :options="statusOptions" placeholder="按状态筛选" />
      </div>
      <n-data-table :columns="columns" :data="filteredAgents" :loading="loading" :pagination="false" />
    </n-card>

    <n-modal
      v-model:show="showModal"
      preset="card"
      :title="editingAgent ? '编辑 Agent' : '新增 Agent'"
      class="form-modal"
      :style="{ width: 'min(820px, calc(100vw - 32px))' }"
    >
      <n-form :model="formValue" label-placement="top">
        <div class="form-grid">
          <n-form-item label="Agent 标识">
            <n-input v-model:value="formValue.agentKey" placeholder="例如 official-writing" />
          </n-form-item>
          <n-form-item label="Agent 名称">
            <n-input v-model:value="formValue.name" placeholder="例如 公文写作 Agent" />
          </n-form-item>
          <n-form-item label="简称">
            <n-input v-model:value="formValue.shortName" placeholder="例如 文" />
          </n-form-item>
          <n-form-item label="默认模型">
            <n-select
              v-model:value="formValue.defaultModelId"
              clearable
              filterable
              :options="modelOptions"
              placeholder="为空则使用系统默认模型"
            />
          </n-form-item>
          <n-form-item label="启用状态">
            <n-switch v-model:value="formValue.enabled" />
          </n-form-item>
          <n-form-item label="排序">
            <n-input-number v-model:value="formValue.sort" :min="0" />
          </n-form-item>
        </div>
        <n-form-item label="描述">
          <n-input v-model:value="formValue.description" placeholder="用于用户端 Agent 列表展示" />
        </n-form-item>
        <n-form-item label="系统提示词">
          <n-input
            v-model:value="formValue.systemPrompt"
            type="textarea"
            :autosize="{ minRows: 8, maxRows: 16 }"
            placeholder="描述 Agent 的角色、边界、输出风格和注意事项"
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
import { createAgent, deleteAgent, fetchAgents, updateAgent } from '../api/agent'
import { fetchAiModels } from '../api/ai'
import type { AgentFormPayload, AgentRecord } from '../types/agent'
import type { AiModelRecord } from '../types/ai'

const message = useMessage()
const loading = ref(false)
const showModal = ref(false)
const keyword = ref('')
const statusFilter = ref<string | null>(null)
const agents = ref<AgentRecord[]>([])
const models = ref<AiModelRecord[]>([])
const editingAgent = ref<AgentRecord | null>(null)

const statusOptions: SelectOption[] = [
  { label: '启用', value: 'true' },
  { label: '停用', value: 'false' }
]

const formValue = reactive<AgentFormPayload>({
  agentKey: '',
  name: '',
  shortName: '',
  description: '',
  systemPrompt: '',
  defaultModelId: null,
  enabled: true,
  sort: 0
})

const modelOptions = computed<SelectOption[]>(() =>
  models.value.map((item) => ({
    label: `${item.displayName}（${item.providerName}）`,
    value: item.modelId
  }))
)

const filteredAgents = computed(() =>
  agents.value.filter((item) => {
    const matchedKeyword =
      !keyword.value ||
      item.agentKey.includes(keyword.value) ||
      item.name.includes(keyword.value) ||
      (item.description ?? '').includes(keyword.value)
    const matchedStatus = statusFilter.value === null || String(item.enabled) === statusFilter.value
    return matchedKeyword && matchedStatus
  })
)

const columns: DataTableColumns<AgentRecord> = [
  { title: '名称', key: 'name', width: 180 },
  { title: '标识', key: 'agentKey', width: 180, ellipsis: { tooltip: true } },
  { title: '描述', key: 'description', ellipsis: { tooltip: true } },
  {
    title: '默认模型',
    key: 'defaultModelName',
    width: 180,
    render: (row) => row.defaultModelName ?? '系统默认'
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
            { onPositiveClick: () => handleDelete(row.agentId) },
            {
              trigger: () => h(NButton, { size: 'small', tertiary: true, type: 'error' }, { default: () => '删除' }),
              default: () => '确认删除当前 Agent 吗？'
            }
          )
        ]
      })
  }
]

function resetForm() {
  formValue.agentKey = ''
  formValue.name = ''
  formValue.shortName = ''
  formValue.description = ''
  formValue.systemPrompt = ''
  formValue.defaultModelId = null
  formValue.enabled = true
  formValue.sort = 0
}

function openCreate() {
  editingAgent.value = null
  resetForm()
  showModal.value = true
}

function openEdit(agent: AgentRecord) {
  editingAgent.value = agent
  formValue.agentKey = agent.agentKey
  formValue.name = agent.name
  formValue.shortName = agent.shortName ?? ''
  formValue.description = agent.description ?? ''
  formValue.systemPrompt = agent.systemPrompt
  formValue.defaultModelId = agent.defaultModelId ?? null
  formValue.enabled = agent.enabled
  formValue.sort = agent.sort
  showModal.value = true
}

async function loadData() {
  loading.value = true
  try {
    const [agentData, modelData] = await Promise.all([fetchAgents(), fetchAiModels()])
    agents.value = agentData
    models.value = modelData
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载 Agent 数据失败')
  } finally {
    loading.value = false
  }
}

async function submitForm() {
  try {
    if (editingAgent.value) {
      await updateAgent(editingAgent.value.agentId, { ...formValue })
      message.success('Agent 更新成功')
    } else {
      await createAgent({ ...formValue })
      message.success('Agent 创建成功')
    }

    showModal.value = false
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存 Agent 失败')
  }
}

async function handleStatusChange(agent: AgentRecord, enabled: boolean) {
  try {
    await updateAgent(agent.agentId, {
      agentKey: agent.agentKey,
      name: agent.name,
      shortName: agent.shortName ?? '',
      description: agent.description ?? '',
      systemPrompt: agent.systemPrompt,
      defaultModelId: agent.defaultModelId ?? null,
      enabled,
      sort: agent.sort
    })
    agent.enabled = enabled
    message.success('Agent 状态已更新')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '更新 Agent 状态失败')
  }
}

async function handleDelete(agentId: number) {
  try {
    await deleteAgent(agentId)
    message.success('Agent 已删除')
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除 Agent 失败')
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
  grid-template-columns: 2fr 1fr;
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
