<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>Skill 库</h2>
        <p>上传本地 Skill 包，重复名称时选择覆盖或重命名。</p>
      </div>
      <n-space>
        <n-button :loading="loading" @click="loadData">
          <template #icon><n-icon :component="ReloadOutlined" /></template>
          刷新
        </n-button>
        <n-button type="primary" :loading="uploading" @click="triggerUpload">
          <template #icon><n-icon :component="UploadOutlined" /></template>
          上传 Skill
        </n-button>
      </n-space>
      <input ref="fileInputRef" class="file-input" type="file" accept=".zip" @change="handleFileChange" />
    </div>

    <n-card :bordered="false" size="small">
      <div class="toolbar">
        <n-input v-model:value="keyword" clearable placeholder="搜索 Skill 名称、标识或说明" />
      </div>
      <n-data-table :columns="columns" :data="filteredSkills" :loading="loading" :pagination="false" />
    </n-card>

    <n-modal
      v-model:show="showDuplicateModal"
      preset="card"
      title="发现同名 Skill"
      :style="{ width: 'min(520px, calc(100vw - 32px))' }"
    >
      <n-descriptions :column="1" size="small" bordered>
        <n-descriptions-item label="名称">{{ pendingImport?.parsedName }}</n-descriptions-item>
        <n-descriptions-item label="说明">{{ pendingImport?.parsedDescription || '无' }}</n-descriptions-item>
        <n-descriptions-item label="文件数">{{ pendingImport?.fileCount ?? 0 }}</n-descriptions-item>
      </n-descriptions>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showDuplicateModal = false">取消</n-button>
          <n-button @click="openRenameModal">重命名</n-button>
          <n-button type="primary" :loading="applyingImport" @click="applyOverwrite">覆盖</n-button>
        </n-space>
      </template>
    </n-modal>

    <n-modal
      v-model:show="showRenameModal"
      preset="card"
      title="重命名 Skill"
      :style="{ width: 'min(480px, calc(100vw - 32px))' }"
    >
      <n-form label-placement="top">
        <n-form-item label="新名称">
          <n-input v-model:value="renameValue" placeholder="请输入新的 Skill 名称" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showRenameModal = false">取消</n-button>
          <n-button type="primary" :loading="applyingImport" @click="applyRename">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <n-modal
      v-model:show="showFilesModal"
      class="file-modal"
      preset="card"
      :title="fileModalTitle"
      :style="{ width: 'min(1080px, calc(100vw - 32px))', maxHeight: 'calc(100vh - 48px)' }"
    >
      <div class="file-browser">
        <aside class="file-tree-pane">
          <div class="pane-title">文件树</div>
          <div class="file-tree-scroll">
            <n-spin :show="filesLoading">
              <n-empty v-if="!filesLoading && !skillFileTree.length" description="暂无文件" />
              <n-tree
                v-else
                block-line
                :data="skillFileTree"
                :expanded-keys="expandedFileKeys"
                :selected-keys="selectedFileKeys"
                :render-prefix="renderFileTreePrefix"
                @update:expanded-keys="handleExpandedFileKeys"
                @update:selected-keys="handleSelectedFileKeys"
              />
            </n-spin>
          </div>
        </aside>
        <section class="file-preview-pane">
          <div class="pane-title">文件预览</div>
          <div class="file-preview-scroll">
            <n-spin :show="fileContentLoading">
              <n-empty v-if="!selectedFilePath && !fileContentLoading" description="请选择左侧文件" />
              <div v-else-if="fileContent" class="preview-content">
                <div class="preview-meta">
                  <div>
                    <span>路径</span>
                    <strong>{{ fileContent.relativePath }}</strong>
                  </div>
                  <div>
                    <span>大小</span>
                    <strong>{{ formatBytes(fileContent.sizeBytes) }}</strong>
                  </div>
                </div>
                <article
                  v-if="fileContent.previewable && isMarkdownPath(fileContent.relativePath)"
                  class="markdown-preview"
                  v-html="renderMarkdownPreview(fileContent.content ?? '')"
                />
                <pre v-else-if="fileContent.previewable" class="preview-code">{{ fileContent.content }}</pre>
                <n-empty v-else :description="fileContent.message || '该文件暂不支持预览'" />
              </div>
            </n-spin>
          </div>
        </section>
      </div>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import type { DataTableColumns, TreeOption } from 'naive-ui'
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  NPopconfirm,
  NSpin,
  NSpace,
  NTree,
  useMessage
} from 'naive-ui'
import { DeleteOutlined, FileSearchOutlined, FileTextOutlined, FolderOpenOutlined, ReloadOutlined, UploadOutlined } from '@vicons/antd'
import {
  applySkillImport,
  deleteSkill,
  fetchSkillFileContent,
  fetchSkillFiles,
  fetchSkills,
  uploadSkillArchive
} from '../api/skill'
import type { SkillFileContent, SkillFileRecord, SkillImportPreview, SkillRecord } from '../types/skill'
import { isMarkdownPath, renderMarkdownPreview } from '../utils/markdown-preview'

type SkillTreeOption = TreeOption & {
  path: string
  directory: boolean
  file?: SkillFileRecord
  children?: SkillTreeOption[]
}

const message = useMessage()
const loading = ref(false)
const uploading = ref(false)
const applyingImport = ref(false)
const filesLoading = ref(false)
const fileContentLoading = ref(false)
const keyword = ref('')
const skills = ref<SkillRecord[]>([])
const skillFiles = ref<SkillFileRecord[]>([])
const fileContent = ref<SkillFileContent | null>(null)
const expandedFileKeys = ref<string[]>([])
const selectedFilePath = ref<string | null>(null)
const currentFileSkill = ref<SkillRecord | null>(null)
const pendingImport = ref<SkillImportPreview | null>(null)
const showDuplicateModal = ref(false)
const showRenameModal = ref(false)
const showFilesModal = ref(false)
const renameValue = ref('')
const fileInputRef = ref<HTMLInputElement | null>(null)

const filteredSkills = computed(() =>
  skills.value.filter((item) => {
    const text = `${item.name} ${item.skillKey} ${item.description ?? ''}`.toLowerCase()
    return !keyword.value || text.includes(keyword.value.trim().toLowerCase())
  })
)

const fileModalTitle = computed(() => (currentFileSkill.value ? `${currentFileSkill.value.name} 文件清单` : '文件清单'))

const skillFileTree = computed(() => buildFileTree(skillFiles.value))

const selectedFileKeys = computed(() => (selectedFilePath.value ? [selectedFilePath.value] : []))

const columns: DataTableColumns<SkillRecord> = [
  { title: '名称', key: 'name', width: 180 },
  { title: '标识', key: 'skillKey', width: 180, ellipsis: { tooltip: true } },
  { title: '说明', key: 'description', ellipsis: { tooltip: true } },
  { title: '来源文件', key: 'originalFilename', width: 180, ellipsis: { tooltip: true } },
  { title: '文件数', key: 'fileCount', width: 90 },
  {
    title: '更新时间',
    key: 'updatedAt',
    width: 180,
    render: (row) => formatDateTime(row.updatedAt)
  },
  {
    title: '操作',
    key: 'actions',
    width: 180,
    render: (row) =>
      h(NSpace, { wrap: false }, {
        default: () => [
          h(
            NButton,
            { size: 'small', tertiary: true, onClick: () => openFiles(row) },
            {
              icon: () => h(NIcon, { component: FileSearchOutlined }),
              default: () => '文件'
            }
          ),
          h(
            NPopconfirm,
            { onPositiveClick: () => handleDelete(row.skillId) },
            {
              trigger: () =>
                h(
                  NButton,
                  { size: 'small', tertiary: true, type: 'error' },
                  {
                    icon: () => h(NIcon, { component: DeleteOutlined }),
                    default: () => '删除'
                  }
                ),
              default: () => '确认删除当前 Skill 吗？'
            }
          )
        ]
      })
  }
]

function triggerUpload() {
  fileInputRef.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  uploading.value = true
  try {
    const preview = await uploadSkillArchive(file)
    if (preview.duplicate) {
      pendingImport.value = preview
      renameValue.value = preview.parsedName ? `${preview.parsedName} 副本` : ''
      showDuplicateModal.value = true
      return
    }
    message.success('Skill 上传成功')
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '上传 Skill 失败')
  } finally {
    uploading.value = false
  }
}

function openRenameModal() {
  showDuplicateModal.value = false
  showRenameModal.value = true
}

async function applyOverwrite() {
  if (!pendingImport.value) {
    return
  }
  applyingImport.value = true
  try {
    await applySkillImport(pendingImport.value.importId, 'OVERWRITE')
    message.success('Skill 已覆盖')
    showDuplicateModal.value = false
    pendingImport.value = null
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '覆盖 Skill 失败')
  } finally {
    applyingImport.value = false
  }
}

async function applyRename() {
  const name = renameValue.value.trim()
  if (!pendingImport.value || !name) {
    message.warning('请输入新的 Skill 名称')
    return
  }
  applyingImport.value = true
  try {
    await applySkillImport(pendingImport.value.importId, 'RENAME', name)
    message.success('Skill 已重命名导入')
    showRenameModal.value = false
    pendingImport.value = null
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '重命名导入失败')
  } finally {
    applyingImport.value = false
  }
}

async function openFiles(skill: SkillRecord) {
  currentFileSkill.value = skill
  showFilesModal.value = true
  skillFiles.value = []
  fileContent.value = null
  selectedFilePath.value = null
  expandedFileKeys.value = []
  filesLoading.value = true
  try {
    const files = await fetchSkillFiles(skill.skillId)
    skillFiles.value = files
    expandedFileKeys.value = collectDirectoryKeys(skillFileTree.value)
    const defaultFile = findDefaultFile(files)
    if (defaultFile) {
      selectedFilePath.value = defaultFile.relativePath
      await loadFileContent(defaultFile.relativePath)
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载文件清单失败')
  } finally {
    filesLoading.value = false
  }
}

async function loadFileContent(path: string) {
  if (!currentFileSkill.value) {
    return
  }
  fileContentLoading.value = true
  fileContent.value = null
  try {
    fileContent.value = await fetchSkillFileContent(currentFileSkill.value.skillId, path)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载文件内容失败')
  } finally {
    fileContentLoading.value = false
  }
}

function handleExpandedFileKeys(keys: Array<string | number>) {
  expandedFileKeys.value = keys.map(String)
}

function handleSelectedFileKeys(keys: Array<string | number>) {
  const path = keys[0]?.toString()
  if (!path || path === selectedFilePath.value) {
    return
  }
  const file = skillFiles.value.find((item) => !item.directory && item.relativePath === path)
  if (!file) {
    return
  }
  selectedFilePath.value = file.relativePath
  void loadFileContent(file.relativePath)
}

function buildFileTree(files: SkillFileRecord[]) {
  const roots: SkillTreeOption[] = []
  const nodeMap = new Map<string, SkillTreeOption>()

  files.forEach((file) => {
    const parts = file.relativePath.split('/').filter(Boolean)
    let parentChildren = roots
    let currentPath = ''

    parts.forEach((part, index) => {
      currentPath = currentPath ? `${currentPath}/${part}` : part
      const isLast = index === parts.length - 1
      const isDirectory = !isLast || file.directory
      let node = nodeMap.get(currentPath)

      if (!node) {
        node = {
          key: currentPath,
          label: part,
          path: currentPath,
          directory: isDirectory,
          disabled: isDirectory,
          children: isDirectory ? [] : undefined
        }
        nodeMap.set(currentPath, node)
        parentChildren.push(node)
      }

      if (isLast) {
        node.file = file
        node.directory = file.directory
        node.disabled = file.directory
        if (!file.directory) {
          node.children = undefined
        }
      }

      if (node.children) {
        parentChildren = node.children
      }
    })
  })

  sortTree(roots)
  return roots
}

function sortTree(nodes: SkillTreeOption[]) {
  nodes.sort((left, right) => {
    if (left.directory !== right.directory) {
      return left.directory ? -1 : 1
    }
    return String(left.label).localeCompare(String(right.label))
  })
  nodes.forEach((node) => {
    if (node.children) {
      sortTree(node.children)
    }
  })
}

function collectDirectoryKeys(nodes: SkillTreeOption[]) {
  const keys: string[] = []
  nodes.forEach((node) => {
    if (node.directory) {
      keys.push(String(node.key))
    }
    if (node.children) {
      keys.push(...collectDirectoryKeys(node.children))
    }
  })
  return keys
}

function findDefaultFile(files: SkillFileRecord[]) {
  return (
    files.find((file) => !file.directory && file.relativePath.toLowerCase().endsWith('skill.md')) ??
    files.find((file) => !file.directory)
  )
}

function renderFileTreePrefix({ option }: { option: TreeOption }) {
  const treeOption = option as SkillTreeOption
  return h(NIcon, {
    component: treeOption.directory ? FolderOpenOutlined : FileTextOutlined
  })
}

async function handleDelete(skillId: number) {
  try {
    await deleteSkill(skillId)
    message.success('Skill 已删除')
    await loadData()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除 Skill 失败')
  }
}

async function loadData() {
  loading.value = true
  try {
    skills.value = await fetchSkills()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载 Skill 数据失败')
  } finally {
    loading.value = false
  }
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

function formatBytes(value: number) {
  if (value < 1024) {
    return `${value} B`
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`
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

.file-input {
  display: none;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(240px, 420px);
  gap: 12px;
  margin-bottom: 16px;
}

.file-browser {
  display: grid;
  grid-template-columns: minmax(220px, 32%) minmax(0, 1fr);
  height: min(560px, calc(100vh - 180px));
  min-height: 340px;
  overflow: hidden;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.file-tree-pane,
.file-preview-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 14px;
}

.file-tree-pane {
  overflow: hidden;
  border-right: 1px solid #e2e8f0;
  background: #f8fafc;
}

.file-preview-pane {
  overflow: hidden;
  background: #ffffff;
}

.pane-title {
  margin-bottom: 12px;
  color: #334155;
  font-size: 14px;
  font-weight: 600;
}

.file-tree-scroll,
.file-preview-scroll {
  min-height: 0;
  flex: 1;
  overflow: auto;
}

.preview-content {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 12px;
}

.preview-meta {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #f8fafc;
}

.preview-meta div {
  min-width: 0;
}

.preview-meta span {
  display: block;
  margin-bottom: 4px;
  color: #64748b;
  font-size: 12px;
}

.preview-meta strong {
  display: block;
  overflow: hidden;
  color: #0f172a;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-code {
  min-height: 0;
  margin: 0;
  padding: 14px;
  border: 1px solid #dbe4ef;
  border-radius: 6px;
  background: #0f172a;
  color: #e2e8f0;
  font-family: Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.markdown-preview {
  min-height: 0;
  padding: 18px 20px;
  border: 1px solid #dbe4ef;
  border-radius: 6px;
  background: #ffffff;
  color: #172033;
  font-size: 14px;
  line-height: 1.75;
}

.markdown-preview :deep(h1),
.markdown-preview :deep(h2),
.markdown-preview :deep(h3),
.markdown-preview :deep(h4),
.markdown-preview :deep(h5),
.markdown-preview :deep(h6) {
  margin: 18px 0 10px;
  color: #0f172a;
  font-weight: 650;
}

.markdown-preview :deep(h1) {
  padding-bottom: 8px;
  border-bottom: 1px solid #e2e8f0;
  font-size: 22px;
}

.markdown-preview :deep(h2) {
  font-size: 18px;
}

.markdown-preview :deep(h3) {
  font-size: 16px;
}

.markdown-preview :deep(p),
.markdown-preview :deep(ul),
.markdown-preview :deep(ol),
.markdown-preview :deep(blockquote),
.markdown-preview :deep(pre) {
  margin: 0 0 12px;
}

.markdown-preview :deep(ul),
.markdown-preview :deep(ol) {
  padding-left: 22px;
}

.markdown-preview :deep(code) {
  border-radius: 4px;
  background: #eef2ff;
  color: #1e3a8a;
  font-family: Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 13px;
  padding: 2px 5px;
}

.markdown-preview :deep(pre) {
  overflow: auto;
  padding: 12px;
  border-radius: 6px;
  background: #0f172a;
  color: #e2e8f0;
}

.markdown-preview :deep(pre code) {
  padding: 0;
  background: transparent;
  color: inherit;
}

.markdown-preview :deep(blockquote) {
  padding: 8px 12px;
  border-left: 3px solid #94a3b8;
  background: #f8fafc;
  color: #475569;
}

:deep(.n-card) {
  flex: 1;
}

@media (max-width: 900px) {
  .page-header {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar {
    grid-template-columns: 1fr;
  }

  .file-browser {
    grid-template-columns: 1fr;
    height: min(620px, calc(100vh - 160px));
  }

  .file-tree-pane {
    min-height: 180px;
    border-right: 0;
    border-bottom: 1px solid #e2e8f0;
  }

  .preview-meta {
    grid-template-columns: 1fr;
  }
}
</style>
