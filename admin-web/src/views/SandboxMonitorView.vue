<template>
  <div class="page sandbox-monitor">
    <div class="page-header">
      <div>
        <h2>Sandbox 运维</h2>
        <p>运行态、资源占用、残留资源和最近执行状态。</p>
      </div>
      <n-space align="center" :wrap="true">
        <n-select
          v-model:value="refreshIntervalSeconds"
          size="small"
          :options="refreshIntervalOptions"
          class="interval-select"
        />
        <n-switch v-model:value="autoRefresh" size="small">
          <template #checked>自动刷新</template>
          <template #unchecked>手动刷新</template>
        </n-switch>
        <n-button :loading="loading" @click="loadAll">
          <template #icon>
            <n-icon><ReloadOutlined /></n-icon>
          </template>
          刷新
        </n-button>
      </n-space>
    </div>

    <section class="metric-grid">
      <n-card v-for="item in metrics" :key="item.key" :bordered="false" size="small">
        <div class="metric-card">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <small>{{ item.tip }}</small>
        </div>
      </n-card>
    </section>

    <n-alert v-if="overview && !overview.serviceHealthy" type="error" :bordered="false">
      sandbox-service 当前不可用，面板仅展示数据库侧可见状态。
    </n-alert>

    <n-tabs v-model:value="activeTab" type="line" animated>
      <n-tab-pane name="overview" tab="概览">
        <section class="runtime-grid">
          <n-card :bordered="false" size="small" title="运行态摘要">
            <div class="summary-strip">
              <div>
                <span>活动会话</span>
                <strong>{{ overview?.activeSessions ?? 0 }}</strong>
              </div>
              <div>
                <span>运行执行</span>
                <strong>{{ overview?.runningExecutions ?? 0 }}</strong>
              </div>
              <div>
                <span>运行目录</span>
                <strong>{{ resources?.runDirs.length ?? 0 }}</strong>
              </div>
            </div>
            <n-data-table
              size="small"
              :columns="runtimeSessionColumns"
              :data="runtimeSessions"
              :pagination="false"
              :bordered="false"
              :scroll-x="860"
            />
          </n-card>

          <n-card :bordered="false" size="small" title="风险">
            <div v-if="overview?.risks.length" class="risk-list">
              <n-thing v-for="risk in overview.risks" :key="`${risk.code}-${risk.targetId ?? ''}`">
                <template #header>
                  <n-space align="center" :wrap="false">
                    <n-tag :type="buildRiskTagType(risk.level)" :bordered="false" size="small">
                      {{ risk.level }}
                    </n-tag>
                    <span>{{ risk.title }}</span>
                  </n-space>
                </template>
                <template #description>
                  <div class="risk-description">
                    <span>{{ risk.message }}</span>
                    <small v-if="risk.recommendedAction">{{ risk.recommendedAction }}</small>
                  </div>
                </template>
              </n-thing>
            </div>
            <n-empty v-else description="暂无风险" />
          </n-card>
        </section>
      </n-tab-pane>

      <n-tab-pane name="sessions" tab="会话">
        <n-card :bordered="false" size="small">
          <div class="toolbar">
            <n-select
              v-model:value="sessionStatusFilter"
              clearable
              :options="sessionStatusOptions"
              placeholder="运行态"
            />
            <n-input v-model:value="sessionKeyword" clearable placeholder="会话、VM、会话ID" />
          </div>
          <n-data-table
            size="small"
            :columns="sessionColumns"
            :data="filteredSessions"
            :loading="loading"
            :pagination="{ pageSize: 12 }"
            :scroll-x="1240"
          />
        </n-card>
      </n-tab-pane>

      <n-tab-pane name="executions" tab="执行">
        <section class="split-grid">
          <n-card :bordered="false" size="small" title="最近执行">
            <div class="toolbar toolbar-compact">
              <n-select
                v-model:value="executionStatusFilter"
                clearable
                :options="executionStatusOptions"
                placeholder="执行状态"
              />
              <n-input v-model:value="executionKeyword" clearable placeholder="工具、命令、错误" />
            </div>
            <n-data-table
              size="small"
              :columns="executionColumns"
              :data="filteredExecutions"
              :loading="loading"
              :pagination="{ pageSize: 10 }"
              :scroll-x="1280"
            />
          </n-card>

          <n-card :bordered="false" size="small" title="最近产物">
            <n-data-table
              size="small"
              :columns="artifactColumns"
              :data="artifacts"
              :loading="loading"
              :pagination="{ pageSize: 10 }"
              :scroll-x="900"
            />
          </n-card>
        </section>
      </n-tab-pane>

      <n-tab-pane name="resources" tab="资源">
        <section class="resource-grid">
          <n-card :bordered="false" size="small" title="运行目录">
            <n-data-table
              size="small"
              :columns="runDirColumns"
              :data="resources?.runDirs ?? []"
              :loading="loading"
              :pagination="{ pageSize: 10 }"
              :scroll-x="1050"
            />
          </n-card>
          <n-card :bordered="false" size="small" title="Firecracker 进程">
            <n-data-table
              size="small"
              :columns="processColumns"
              :data="resources?.firecrackerProcesses ?? []"
              :loading="loading"
              :pagination="{ pageSize: 10 }"
              :scroll-x="1050"
            />
          </n-card>
          <n-card :bordered="false" size="small" title="Tap 设备">
            <n-data-table
              size="small"
              :columns="tapColumns"
              :data="resources?.taps ?? []"
              :loading="loading"
              :pagination="{ pageSize: 10 }"
              :scroll-x="720"
            />
          </n-card>
          <n-card :bordered="false" size="small" title="残留资源">
            <n-data-table
              size="small"
              :columns="orphanColumns"
              :data="resources?.orphans ?? []"
              :loading="loading"
              :pagination="{ pageSize: 10 }"
              :scroll-x="820"
            />
          </n-card>
        </section>
      </n-tab-pane>

      <n-tab-pane name="logs" tab="日志尾部">
        <n-card :bordered="false" size="small">
          <div class="toolbar log-toolbar">
            <n-input-number v-model:value="logLines" :min="20" :max="500" :step="20" />
            <n-button :loading="logsLoading" @click="loadLogs">
              <template #icon>
                <n-icon><FileSearchOutlined /></n-icon>
              </template>
              读取日志
            </n-button>
          </div>
          <pre class="log-output">{{ logs || '暂无日志' }}</pre>
        </n-card>
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { DataTableColumns, SelectOption } from 'naive-ui'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NIcon,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NSwitch,
  NTabPane,
  NTabs,
  NTag,
  NThing,
  useMessage
} from 'naive-ui'
import { FileSearchOutlined, ReloadOutlined } from '@vicons/antd'
import {
  fetchSandboxArtifacts,
  fetchSandboxExecutions,
  fetchSandboxLogTail,
  fetchSandboxOverview,
  fetchSandboxResources,
  fetchSandboxSessions
} from '../api/sandbox-admin'
import type {
  SandboxAdminArtifactItem,
  SandboxAdminExecutionItem,
  SandboxAdminSessionItem,
  SandboxOverview,
  SandboxProcessInfo,
  SandboxResourceSnapshot,
  SandboxRunDirInfo,
  SandboxRuntimeSession,
  SandboxTapInfo
} from '../types/sandbox-admin'
import {
  buildRiskTagType,
  buildStatusTagType,
  formatBytes,
  formatDateTime,
  formatDurationMs,
  formatDurationSeconds,
  formatStatus
} from './sandbox-admin.helpers'

const message = useMessage()
const loading = ref(false)
const logsLoading = ref(false)
const activeTab = ref('overview')
const autoRefresh = ref(false)
const refreshIntervalSeconds = ref(30)
const overview = ref<SandboxOverview | null>(null)
const resources = ref<SandboxResourceSnapshot | null>(null)
const sessions = ref<SandboxAdminSessionItem[]>([])
const executions = ref<SandboxAdminExecutionItem[]>([])
const artifacts = ref<SandboxAdminArtifactItem[]>([])
const logs = ref('')
const logLines = ref(120)
const sessionStatusFilter = ref<string | null>(null)
const sessionKeyword = ref('')
const executionStatusFilter = ref<string | null>(null)
const executionKeyword = ref('')
let refreshTimer: number | undefined

const refreshIntervalOptions: SelectOption[] = [
  { label: '15 秒', value: 15 },
  { label: '30 秒', value: 30 },
  { label: '60 秒', value: 60 }
]

const sessionStatusOptions: SelectOption[] = [
  { label: '活跃', value: 'ACTIVE' },
  { label: '空闲', value: 'IDLE' },
  { label: '已关闭', value: 'CLOSED' },
  { label: '启动中', value: 'STARTING' }
]

const executionStatusOptions: SelectOption[] = [
  { label: '运行中', value: 'RUNNING' },
  { label: '完成', value: 'COMPLETED' },
  { label: '失败', value: 'FAILED' },
  { label: '超时', value: 'TIMEOUT' }
]

const runtimeSessions = computed<SandboxRuntimeSession[]>(() =>
  sessions.value.map((item) => ({
    sandboxSessionId: item.sandboxSessionId,
    conversationId: item.conversationId ? String(item.conversationId) : null,
    vmId: item.vmId,
    lastActiveAt: item.lastActiveAt,
    activeUses: 0,
    idleSeconds: item.idleSeconds,
    idleTimeoutSeconds: 0,
    runDir: item.runDir,
    tapName: item.tapName,
    firecrackerPid: item.firecrackerPid,
    runtimeStatus: item.runtimeStatus
  }))
)

const metrics = computed(() => [
  {
    key: 'healthy',
    label: '服务状态',
    value: overview.value?.serviceHealthy ? '正常' : '异常',
    tip: `活动会话 ${overview.value?.activeSessions ?? 0}`
  },
  {
    key: 'db-sessions',
    label: '数据库打开会话',
    value: String(overview.value?.dbOpenSessions ?? 0),
    tip: `运行中执行 ${overview.value?.runningExecutions ?? 0}`
  },
  {
    key: 'firecracker',
    label: 'Firecracker',
    value: String(overview.value?.firecrackerProcesses ?? 0),
    tip: `Tap ${overview.value?.tapDevices ?? 0}`
  },
  {
    key: 'run-dir',
    label: '运行目录',
    value: formatBytes(overview.value?.runDirBytes ?? resources.value?.runDirBytes),
    tip: `${resources.value?.runDirs.length ?? 0} 个目录`
  },
  {
    key: 'orphans',
    label: '残留资源',
    value: String(overview.value?.orphanResources ?? resources.value?.orphans.length ?? 0),
    tip: `风险 ${overview.value?.risks.length ?? 0}`
  },
  {
    key: 'failed',
    label: '近期失败执行',
    value: String(overview.value?.recentFailedExecutions ?? 0),
    tip: `刷新 ${formatDateTime(resources.value?.scannedAt)}`
  }
])

const filteredSessions = computed(() =>
  sessions.value.filter((item) => {
    const status = item.runtimeStatus || item.dbStatus
    const matchedStatus = !sessionStatusFilter.value || status === sessionStatusFilter.value
    const keyword = sessionKeyword.value.trim()
    const matchedKeyword = !keyword || [
      item.sandboxSessionId,
      item.vmId,
      item.runDir,
      item.tapName,
      item.conversationId ? String(item.conversationId) : ''
    ].some((value) => (value ?? '').includes(keyword))
    return matchedStatus && matchedKeyword
  })
)

const filteredExecutions = computed(() =>
  executions.value.filter((item) => {
    const matchedStatus = !executionStatusFilter.value || item.status === executionStatusFilter.value
    const keyword = executionKeyword.value.trim()
    const matchedKeyword = !keyword || [
      item.toolName,
      item.command,
      item.workingDir,
      item.errorMessage,
      item.conversationId ? String(item.conversationId) : ''
    ].some((value) => (value ?? '').includes(keyword))
    return matchedStatus && matchedKeyword
  })
)

const runtimeSessionColumns: DataTableColumns<SandboxRuntimeSession> = [
  { title: '会话', key: 'sandboxSessionId', width: 220, ellipsis: { tooltip: true } },
  { title: '对话', key: 'conversationId', width: 90, render: (row) => row.conversationId ?? '--' },
  { title: '运行态', key: 'runtimeStatus', width: 110, render: (row) => renderStatusTag(row.runtimeStatus) },
  { title: 'VM', key: 'vmId', width: 180, ellipsis: { tooltip: true }, render: (row) => row.vmId ?? '--' },
  { title: 'PID', key: 'firecrackerPid', width: 90, render: (row) => row.firecrackerPid ?? '--' },
  { title: '空闲', key: 'idleSeconds', width: 110, render: (row) => formatDurationSeconds(row.idleSeconds) },
  { title: '最后活跃', key: 'lastActiveAt', width: 180, render: (row) => formatDateTime(row.lastActiveAt) }
]

const sessionColumns: DataTableColumns<SandboxAdminSessionItem> = [
  { title: '会话', key: 'sandboxSessionId', width: 220, ellipsis: { tooltip: true } },
  { title: '对话', key: 'conversationId', width: 90, render: (row) => row.conversationId ?? '--' },
  { title: '运行态', key: 'runtimeStatus', width: 110, render: (row) => renderStatusTag(row.runtimeStatus) },
  { title: '库状态', key: 'dbStatus', width: 110, render: (row) => renderStatusTag(row.dbStatus) },
  { title: 'VM', key: 'vmId', width: 180, ellipsis: { tooltip: true }, render: (row) => row.vmId ?? '--' },
  { title: 'PID', key: 'firecrackerPid', width: 90, render: (row) => row.firecrackerPid ?? '--' },
  { title: '空闲', key: 'idleSeconds', width: 110, render: (row) => formatDurationSeconds(row.idleSeconds) },
  { title: '最后活跃', key: 'lastActiveAt', width: 180, render: (row) => formatDateTime(row.lastActiveAt) },
  { title: '运行目录', key: 'runDir', ellipsis: { tooltip: true }, render: (row) => row.runDir ?? '--' }
]

const executionColumns: DataTableColumns<SandboxAdminExecutionItem> = [
  { title: 'ID', key: 'id', width: 80 },
  { title: '工具', key: 'toolName', width: 150, ellipsis: { tooltip: true }, render: (row) => row.toolName ?? '--' },
  { title: '状态', key: 'status', width: 100, render: (row) => renderStatusTag(row.status) },
  { title: '退出码', key: 'exitCode', width: 90, render: (row) => row.exitCode ?? '--' },
  { title: '耗时', key: 'durationMs', width: 120, render: (row) => formatDurationMs(row.durationMs) },
  { title: '命令', key: 'command', ellipsis: { tooltip: true }, render: (row) => row.command ?? '--' },
  { title: '错误', key: 'errorMessage', width: 220, ellipsis: { tooltip: true }, render: (row) => row.errorMessage ?? '--' },
  { title: '开始时间', key: 'startedAt', width: 180, render: (row) => formatDateTime(row.startedAt) }
]

const artifactColumns: DataTableColumns<SandboxAdminArtifactItem> = [
  { title: '文件', key: 'fileName', width: 220, ellipsis: { tooltip: true }, render: (row) => row.fileName ?? '--' },
  { title: '大小', key: 'sizeBytes', width: 110, render: (row) => formatBytes(row.sizeBytes) },
  { title: '类型', key: 'contentType', width: 160, ellipsis: { tooltip: true }, render: (row) => row.contentType ?? '--' },
  { title: '版本', key: 'version', width: 90, render: (row) => row.version ?? '--' },
  { title: '路径', key: 'relativePath', ellipsis: { tooltip: true }, render: (row) => row.relativePath ?? '--' },
  { title: '创建时间', key: 'createdAt', width: 180, render: (row) => formatDateTime(row.createdAt) }
]

const runDirColumns: DataTableColumns<SandboxRunDirInfo> = [
  { title: 'VM', key: 'vmId', width: 180, ellipsis: { tooltip: true }, render: (row) => row.vmId ?? '--' },
  { title: '状态', key: 'resourceStatus', width: 130, render: (row) => renderStatusTag(row.resourceStatus) },
  { title: '大小', key: 'sizeBytes', width: 110, render: (row) => formatBytes(row.sizeBytes) },
  { title: 'Socket', key: 'hasSocket', width: 90, render: (row) => (row.hasSocket ? '有' : '无') },
  { title: '绑定', key: 'bound', width: 90, render: (row) => (row.bound ? '是' : '否') },
  { title: '创建时间', key: 'createdAt', width: 180, render: (row) => formatDateTime(row.createdAt) },
  { title: '路径', key: 'path', ellipsis: { tooltip: true } }
]

const processColumns: DataTableColumns<SandboxProcessInfo> = [
  { title: 'PID', key: 'pid', width: 90 },
  { title: '状态', key: 'resourceStatus', width: 130, render: (row) => renderStatusTag(row.resourceStatus) },
  { title: 'VM', key: 'vmId', width: 180, ellipsis: { tooltip: true }, render: (row) => row.vmId ?? '--' },
  { title: 'Socket', key: 'apiSocket', width: 220, ellipsis: { tooltip: true }, render: (row) => row.apiSocket ?? '--' },
  { title: '绑定', key: 'bound', width: 90, render: (row) => (row.bound ? '是' : '否') },
  { title: '运行目录', key: 'runDir', width: 260, ellipsis: { tooltip: true }, render: (row) => row.runDir ?? '--' },
  { title: '参数', key: 'args', ellipsis: { tooltip: true }, render: (row) => row.args ?? '--' }
]

const tapColumns: DataTableColumns<SandboxTapInfo> = [
  { title: '名称', key: 'name', width: 180 },
  { title: '状态', key: 'resourceStatus', width: 130, render: (row) => renderStatusTag(row.resourceStatus) },
  { title: 'VM', key: 'vmId', width: 220, ellipsis: { tooltip: true }, render: (row) => row.vmId ?? '--' },
  { title: '绑定', key: 'bound', width: 90, render: (row) => (row.bound ? '是' : '否') }
]

const orphanColumns = [
  { title: '类型', key: 'type', width: 140 },
  { title: '标识', key: 'id', width: 220, ellipsis: { tooltip: true } },
  { title: '路径', key: 'path', width: 260, ellipsis: { tooltip: true }, render: (row: { path?: string | null }) => row.path ?? '--' },
  { title: '原因', key: 'reason', ellipsis: { tooltip: true } }
]

function renderStatusTag(status?: string | null) {
  return h(NTag, { type: buildStatusTagType(status), bordered: false, size: 'small' }, {
    default: () => formatStatus(status)
  })
}

async function loadAll() {
  loading.value = true
  try {
    const [overviewData, resourceData, sessionData, executionData, artifactData] = await Promise.all([
      fetchSandboxOverview(),
      fetchSandboxResources(),
      fetchSandboxSessions(),
      fetchSandboxExecutions(),
      fetchSandboxArtifacts()
    ])
    overview.value = overviewData
    resources.value = resourceData
    sessions.value = sessionData
    executions.value = executionData
    artifacts.value = artifactData
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载 Sandbox 运维数据失败')
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    logs.value = await fetchSandboxLogTail(logLines.value ?? 100)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '读取 Sandbox 日志失败')
  } finally {
    logsLoading.value = false
  }
}

function resetRefreshTimer() {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
    refreshTimer = undefined
  }
  if (autoRefresh.value) {
    refreshTimer = window.setInterval(() => {
      void loadAll()
    }, refreshIntervalSeconds.value * 1000)
  }
}

watch([autoRefresh, refreshIntervalSeconds], resetRefreshTimer)

onMounted(() => {
  void loadAll()
})

onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
  }
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

.interval-select {
  width: 112px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}

.metric-card {
  display: grid;
  gap: 8px;
  min-height: 94px;
}

.metric-card span,
.metric-card small,
.summary-strip span,
.risk-description small {
  color: #64748b;
}

.metric-card strong {
  overflow: hidden;
  color: #0f172a;
  font-size: 24px;
  line-height: 1.15;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-grid,
.split-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(360px, 0.8fr);
  gap: 16px;
}

.resource-grid {
  display: grid;
  gap: 16px;
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.summary-strip div {
  display: grid;
  gap: 4px;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.summary-strip strong {
  color: #0f172a;
  font-size: 22px;
  line-height: 1;
}

.risk-list {
  display: grid;
  gap: 14px;
}

.risk-description {
  display: grid;
  gap: 6px;
}

.toolbar {
  display: grid;
  grid-template-columns: 180px minmax(220px, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}

.toolbar-compact {
  grid-template-columns: 150px minmax(220px, 1fr);
}

.log-toolbar {
  grid-template-columns: 160px auto;
  justify-content: start;
}

.log-output {
  min-height: 420px;
  max-height: 62vh;
  margin: 0;
  overflow: auto;
  padding: 14px;
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
  .metric-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .page-header,
  .runtime-grid,
  .split-grid {
    grid-template-columns: 1fr;
  }

  .page-header {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar,
  .toolbar-compact,
  .log-toolbar {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .metric-grid,
  .summary-strip {
    grid-template-columns: 1fr;
  }
}
</style>
