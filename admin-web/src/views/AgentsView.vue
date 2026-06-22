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

    <n-modal
      v-model:show="showSkillModal"
      preset="card"
      :title="skillModalAgent ? `${skillModalAgent.name} 绑定 Skill` : '绑定 Skill'"
      class="form-modal"
      :style="{ width: 'min(640px, calc(100vw - 32px))' }"
    >
      <n-form label-placement="top">
        <n-form-item label="Skill">
          <n-select
            v-model:value="selectedSkillIds"
            multiple
            filterable
            clearable
            :loading="skillLoading"
            :options="skillOptions"
            placeholder="选择后台绑定给该 Agent 的 Skill"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showSkillModal = false">取消</n-button>
          <n-button type="primary" :loading="skillSaving" @click="submitSkillBindings">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <n-modal
      v-model:show="showToolModal"
      preset="card"
      :title="toolModalAgent ? `${toolModalAgent.name} 绑定工具` : '绑定工具'"
      class="form-modal"
      :style="{ width: 'min(960px, calc(100vw - 32px))' }"
    >
      <div class="tool-modal-body">
        <n-input
          v-model:value="toolKeyword"
          clearable
          placeholder="搜索工具名、展示名、描述或 MCP 服务名"
        />
        <n-data-table
          v-model:checked-row-keys="selectedToolNames"
          :columns="toolColumns"
          :data="filteredToolOptions"
          :loading="toolLoading"
          :pagination="{ pageSize: 8 }"
          :row-key="toolRowKey"
          :bordered="false"
          :single-line="false"
          :scroll-x="860"
        />
      </div>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showToolModal = false">取消</n-button>
          <n-button type="primary" :loading="toolSaving" @click="submitToolBindings">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, DataTableRowKey, SelectOption } from 'naive-ui'
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
  createAgent,
  deleteAgent,
  fetchAgents,
  fetchAgentToolBindings,
  fetchAgentToolOptions,
  updateAgent,
  updateAgentToolBindings
} from '../api/agent'
import { fetchAiModels } from '../api/ai'
import { fetchAgentSkillBindings, fetchAvailableSkills, updateAgentSkillBindings } from '../api/skill'
import type { AgentFormPayload, AgentRecord, AgentToolOptionRecord } from '../types/agent'
import type { AiModelRecord } from '../types/ai'
import type { SkillOptionRecord } from '../types/skill'

const message = useMessage()
const loading = ref(false)
const showModal = ref(false)
const keyword = ref('')
const statusFilter = ref<string | null>(null)
const agents = ref<AgentRecord[]>([])
const models = ref<AiModelRecord[]>([])
const availableSkills = ref<SkillOptionRecord[]>([])
const editingAgent = ref<AgentRecord | null>(null)
const skillModalAgent = ref<AgentRecord | null>(null)
const showSkillModal = ref(false)
const skillLoading = ref(false)
const skillSaving = ref(false)
const selectedSkillIds = ref<number[]>([])
const showToolModal = ref(false)
const toolModalAgent = ref<AgentRecord | null>(null)
const toolLoading = ref(false)
const toolSaving = ref(false)
const toolKeyword = ref('')
const toolOptions = ref<AgentToolOptionRecord[]>([])
const selectedToolNames = ref<DataTableRowKey[]>([])

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

const skillOptions = computed<SelectOption[]>(() =>
  availableSkills.value.map((item) => ({
    label: item.description ? `${item.name}（${item.description}）` : item.name,
    value: item.skillId
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

const filteredToolOptions = computed(() => {
  const keywordText = toolKeyword.value.trim().toLowerCase()
  if (!keywordText) {
    return toolOptions.value
  }

  return toolOptions.value.filter((item) =>
    [item.toolName, item.displayName, item.description, item.serverName].some((value) =>
      (value ?? '').toLowerCase().includes(keywordText)
    )
  )
})

const toolColumns = computed<DataTableColumns<AgentToolOptionRecord>>(() => [
  {
    type: 'selection',
    disabled: (row) => !isToolSelectable(row)
  },
  {
    title: '工具名 / 展示名',
    key: 'toolName',
    minWidth: 240,
    ellipsis: { tooltip: true },
    render: (row) =>
      h('div', { class: 'tool-name-cell' }, [
        h('div', { class: 'tool-display-name' }, row.displayName || row.toolName),
        h('div', { class: 'tool-raw-name' }, row.toolName)
      ])
  },
  {
    title: 'MCP 服务名',
    key: 'serverName',
    minWidth: 160,
    render: (row) => row.serverName || row.serverKey || '-'
  },
  {
    title: '描述',
    key: 'description',
    minWidth: 260,
    ellipsis: { tooltip: true },
    render: (row) => row.description || '暂无描述'
  },
  {
    title: '状态',
    key: 'status',
    width: 190,
    render: (row) =>
      h(NSpace, { size: 6 }, {
        default: () => [
          h(NTag, { type: row.serverEnabled ? 'success' : 'warning', bordered: false }, {
            default: () => (row.serverEnabled ? '服务启用' : '服务停用')
          }),
          h(NTag, { type: row.toolEnabled ? 'success' : 'warning', bordered: false }, {
            default: () => (row.toolEnabled ? '工具启用' : '工具停用')
          })
        ]
      })
  }
])

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
    width: 360,
    render: (row) =>
      h(NSpace, { wrap: false }, {
        default: () => [
          h(NButton, { size: 'small', tertiary: true, onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(NButton, { size: 'small', tertiary: true, onClick: () => openSkills(row) }, { default: () => 'Skill' }),
          h(NButton, { size: 'small', tertiary: true, onClick: () => openTools(row) }, { default: () => '工具' }),
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

async function openSkills(agent: AgentRecord) {
  skillModalAgent.value = agent
  showSkillModal.value = true
  skillLoading.value = true
  try {
    const [skillData, bindingData] = await Promise.all([
      availableSkills.value.length ? Promise.resolve(availableSkills.value) : fetchAvailableSkills(),
      fetchAgentSkillBindings(agent.agentId)
    ])
    availableSkills.value = skillData
    selectedSkillIds.value = bindingData
      .slice()
      .sort((a, b) => a.sort - b.sort)
      .map((item) => item.skillId)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载 Agent Skill 绑定失败')
  } finally {
    skillLoading.value = false
  }
}

function toolRowKey(row: AgentToolOptionRecord) {
  return row.toolName
}

function isToolSelectable(tool: AgentToolOptionRecord) {
  return Boolean(tool.serverEnabled && tool.toolEnabled)
}

function bindingSortValue(binding: { sort?: number | null; sortOrder?: number | null }) {
  return binding.sortOrder ?? binding.sort ?? 0
}

async function openTools(agent: AgentRecord) {
  toolModalAgent.value = agent
  showToolModal.value = true
  toolLoading.value = true
  toolKeyword.value = ''
  toolOptions.value = []
  selectedToolNames.value = []
  try {
    const [optionData, bindingData] = await Promise.all([
      fetchAgentToolOptions(agent.agentId),
      fetchAgentToolBindings(agent.agentId)
    ])
    toolOptions.value = optionData

    const selectableToolNames = new Set(optionData.filter(isToolSelectable).map((item) => item.toolName))
    selectedToolNames.value = bindingData
      .filter((item) => item.enabled)
      .slice()
      .sort((a, b) => bindingSortValue(a) - bindingSortValue(b))
      .map((item) => item.toolName)
      .filter((toolName) => selectableToolNames.has(toolName))
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载 Agent 工具绑定失败')
  } finally {
    toolLoading.value = false
  }
}

async function submitSkillBindings() {
  if (!skillModalAgent.value) {
    return
  }
  skillSaving.value = true
  try {
    await updateAgentSkillBindings(skillModalAgent.value.agentId, selectedSkillIds.value)
    message.success('Agent Skill 绑定已保存')
    showSkillModal.value = false
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存 Agent Skill 绑定失败')
  } finally {
    skillSaving.value = false
  }
}

async function submitToolBindings() {
  if (!toolModalAgent.value) {
    return
  }

  toolSaving.value = true
  try {
    const selectedToolNameSet = new Set(selectedToolNames.value.map(String))
    const tools = toolOptions.value
      .filter((item) => selectedToolNameSet.has(item.toolName) && isToolSelectable(item))
      .map((item, index) => ({
        toolName: item.toolName,
        providerType: item.providerType,
        providerRefId: item.providerRefId,
        enabled: true,
        sortOrder: index
      }))

    await updateAgentToolBindings(toolModalAgent.value.agentId, tools)
    message.success('Agent 工具绑定已保存')
    showToolModal.value = false
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存 Agent 工具绑定失败')
  } finally {
    toolSaving.value = false
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

.tool-modal-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tool-name-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.tool-display-name {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-raw-name {
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
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
