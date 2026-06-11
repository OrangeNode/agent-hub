<template>
  <div class="page operation-logs">
    <div class="page-header">
      <div>
        <h2>操作日志</h2>
        <p>后台高风险操作、接口调用结果和审计上下文。</p>
      </div>
      <n-space>
        <n-button @click="resetFilters">重置</n-button>
        <n-button type="primary" :loading="loading" @click="loadLogs">
          <template #icon>
            <n-icon><SearchOutlined /></n-icon>
          </template>
          查询
        </n-button>
      </n-space>
    </div>

    <n-card :bordered="false" size="small">
      <div class="toolbar">
        <n-date-picker
          v-model:value="createdRange"
          clearable
          type="datetimerange"
          placeholder="操作时间"
        />
        <n-input v-model:value="filters.username" clearable placeholder="用户名" />
        <n-input v-model:value="filters.module" clearable placeholder="模块" />
        <n-input v-model:value="filters.action" clearable placeholder="动作" />
        <n-select v-model:value="filters.status" clearable :options="statusOptions" placeholder="状态" />
        <n-input v-model:value="filters.targetId" clearable placeholder="目标 ID" />
        <n-input v-model:value="filters.keyword" clearable placeholder="关键词" />
        <n-input-number v-model:value="filters.limit" :min="1" :max="500" placeholder="条数" />
      </div>

      <n-data-table
        size="small"
        :columns="columns"
        :data="logs"
        :loading="loading"
        :pagination="{ pageSize: 12 }"
        :scroll-x="1320"
      />
    </n-card>

    <n-drawer v-model:show="showDetail" :width="drawerWidth">
      <n-drawer-content :title="detailTitle" closable>
        <template v-if="selectedLog">
          <n-descriptions :column="2" bordered size="small" label-placement="left">
            <n-descriptions-item label="时间">{{ formatOperationDateTime(selectedLog.createdAt) }}</n-descriptions-item>
            <n-descriptions-item label="状态">
              <n-tag :type="buildOperationStatusTagType(selectedLog.status)" :bordered="false">
                {{ formatOperationStatus(selectedLog.status) }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="用户">{{ selectedLog.username ?? '--' }}</n-descriptions-item>
            <n-descriptions-item label="耗时">{{ formatOperationDuration(selectedLog.durationMs) }}</n-descriptions-item>
            <n-descriptions-item label="模块">{{ selectedLog.module }}</n-descriptions-item>
            <n-descriptions-item label="动作">{{ selectedLog.action }}</n-descriptions-item>
            <n-descriptions-item label="目标">{{ summarizeOperationTarget(selectedLog.targetType, selectedLog.targetId) }}</n-descriptions-item>
            <n-descriptions-item label="类型">{{ selectedLog.operationType ?? '--' }}</n-descriptions-item>
            <n-descriptions-item label="请求">{{ selectedLog.requestMethod ?? '--' }} {{ selectedLog.requestUri ?? '' }}</n-descriptions-item>
            <n-descriptions-item label="IP">{{ selectedLog.clientIp ?? '--' }}</n-descriptions-item>
            <n-descriptions-item label="Trace">{{ selectedLog.traceId ?? '--' }}</n-descriptions-item>
            <n-descriptions-item label="原因">{{ selectedLog.reason ?? '--' }}</n-descriptions-item>
            <n-descriptions-item label="User-Agent" :span="2">{{ selectedLog.userAgent ?? '--' }}</n-descriptions-item>
            <n-descriptions-item v-if="selectedLog.errorMessage" label="错误" :span="2">
              {{ selectedLog.errorCode ? `${selectedLog.errorCode}：` : '' }}{{ selectedLog.errorMessage }}
            </n-descriptions-item>
          </n-descriptions>

          <section class="detail-block">
            <h3>请求参数</h3>
            <pre>{{ formatJsonPreview(selectedLog.paramsJson) }}</pre>
          </section>

          <section class="detail-block">
            <h3>返回结果</h3>
            <pre>{{ formatJsonPreview(selectedLog.resultJson) }}</pre>
          </section>
        </template>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, SelectOption } from 'naive-ui'
import {
  NButton,
  NCard,
  NDataTable,
  NDatePicker,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NIcon,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NTag,
  useMessage
} from 'naive-ui'
import { SearchOutlined } from '@vicons/antd'
import { fetchOperationLogs } from '../api/operation-log'
import type { OperationLogItem, OperationLogQuery } from '../types/operation-log'
import {
  buildOperationStatusTagType,
  formatJsonPreview,
  formatOperationDateTime,
  formatOperationDuration,
  formatOperationStatus,
  normalizeLogLimit,
  summarizeOperationTarget
} from './operation-log.helpers'

const message = useMessage()
const loading = ref(false)
const logs = ref<OperationLogItem[]>([])
const selectedLog = ref<OperationLogItem | null>(null)
const showDetail = ref(false)
const createdRange = ref<[number, number] | null>(null)

const filters = reactive({
  username: '',
  module: '',
  action: '',
  status: null as string | null,
  targetId: '',
  keyword: '',
  limit: 100
})

const statusOptions: SelectOption[] = [
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' }
]

const drawerWidth = computed(() => Math.min(920, window.innerWidth - 32))
const detailTitle = computed(() =>
  selectedLog.value ? `${selectedLog.value.module} / ${selectedLog.value.action}` : '操作日志详情'
)

const columns: DataTableColumns<OperationLogItem> = [
  { title: '时间', key: 'createdAt', width: 180, render: (row) => formatOperationDateTime(row.createdAt) },
  { title: '用户', key: 'username', width: 120, render: (row) => row.username ?? '--' },
  { title: '模块', key: 'module', width: 140, ellipsis: { tooltip: true } },
  { title: '动作', key: 'action', width: 150, ellipsis: { tooltip: true } },
  {
    title: '目标',
    key: 'targetId',
    width: 220,
    ellipsis: { tooltip: true },
    render: (row) => summarizeOperationTarget(row.targetType, row.targetId)
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: (row) =>
      h(NTag, { type: buildOperationStatusTagType(row.status), bordered: false, size: 'small' }, {
        default: () => formatOperationStatus(row.status)
      })
  },
  { title: '耗时', key: 'durationMs', width: 100, render: (row) => formatOperationDuration(row.durationMs) },
  { title: 'IP', key: 'clientIp', width: 140, render: (row) => row.clientIp ?? '--' },
  { title: '原因', key: 'reason', width: 180, ellipsis: { tooltip: true }, render: (row) => row.reason ?? '--' },
  { title: '错误', key: 'errorMessage', ellipsis: { tooltip: true }, render: (row) => row.errorMessage ?? '--' },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    fixed: 'right',
    render: (row) =>
      h(NButton, { size: 'small', tertiary: true, onClick: () => openDetail(row) }, {
        default: () => '详情'
      })
  }
]

function buildQuery(): OperationLogQuery {
  return {
    startAt: createdRange.value?.[0] ? new Date(createdRange.value[0]).toISOString() : null,
    endAt: createdRange.value?.[1] ? new Date(createdRange.value[1]).toISOString() : null,
    username: filters.username,
    module: filters.module,
    action: filters.action,
    status: filters.status,
    targetId: filters.targetId,
    keyword: filters.keyword,
    limit: normalizeLogLimit(filters.limit)
  }
}

async function loadLogs() {
  loading.value = true
  try {
    logs.value = await fetchOperationLogs(buildQuery())
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载操作日志失败')
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  createdRange.value = null
  filters.username = ''
  filters.module = ''
  filters.action = ''
  filters.status = null
  filters.targetId = ''
  filters.keyword = ''
  filters.limit = 100
  void loadLogs()
}

function openDetail(row: OperationLogItem) {
  selectedLog.value = row
  showDetail.value = true
}

onMounted(() => {
  void loadLogs()
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
  grid-template-columns: minmax(260px, 1.4fr) repeat(4, minmax(120px, 0.8fr)) minmax(140px, 0.8fr) minmax(180px, 1fr) 110px;
  gap: 12px;
  margin-bottom: 16px;
}

.detail-block {
  display: grid;
  gap: 8px;
  margin-top: 16px;
}

.detail-block h3 {
  margin: 0;
  font-size: 15px;
}

.detail-block pre {
  max-height: 360px;
  margin: 0;
  overflow: auto;
  padding: 12px;
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #0f172a;
  color: #dbeafe;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

:deep(.n-card__content) {
  min-width: 0;
}

@media (max-width: 1280px) {
  .toolbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .page-header {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
