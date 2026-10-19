<template>
  <aside v-if="workPanelOpen" class="work-panel" aria-label="全部文件">
    <div class="work-panel-header">
      <div>
        <strong>全部文件</strong>
        <span>{{ artifacts.length ? `${artifacts.length} 个文件` : '暂无文件' }}</span>
      </div>
      <div class="work-panel-actions">
        <button
          type="button"
          class="panel-icon-button"
          title="下载全部"
          aria-label="下载全部"
          :disabled="!artifacts.length || downloading"
          @click="emit('download-all')"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 3v12" />
            <path d="m7 10 5 5 5-5" />
            <path d="M5 21h14" />
          </svg>
        </button>
        <button type="button" class="panel-icon-button" title="关闭" aria-label="关闭" @click="emit('close-panel')">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      </div>
    </div>

    <section class="artifact-panel-section">
      <div v-if="loading" class="artifact-empty">正在加载文件...</div>
      <div v-else-if="!artifacts.length" class="artifact-empty">当前会话还没有可下载文件</div>
      <div v-else class="artifact-list">
        <div
          v-for="artifact in artifacts"
          :key="artifact.artifactId"
          class="artifact-list-item"
          :class="{ active: selectedArtifact?.artifactId === artifact.artifactId }"
        >
          <button type="button" class="artifact-preview-trigger" @click="emit('select', artifact)">
            <span class="artifact-file-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <path d="M14 2v6h6" />
                <path d="M8 13h8M8 17h6" />
              </svg>
            </span>
            <span class="artifact-file-meta">
              <strong>{{ artifact.fileName }}</strong>
              <small>{{ formatFileSize(artifact.sizeBytes) }}</small>
            </span>
          </button>
          <span class="artifact-item-actions">
            <button
              type="button"
              title="下载"
              aria-label="下载"
              :disabled="downloading"
              @click="emit('download', artifact)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 3v12" />
                <path d="m7 10 5 5 5-5" />
                <path d="M5 21h14" />
              </svg>
            </button>
          </span>
        </div>
      </div>
    </section>
  </aside>

  <aside v-if="previewOpen" class="artifact-preview-panel" aria-label="文件预览">
    <section class="artifact-preview-dialog" role="dialog" aria-modal="false" aria-labelledby="artifact-preview-title">
      <header class="artifact-preview-dialog-header">
        <div>
          <h3 id="artifact-preview-title">{{ selectedArtifact?.fileName || '文件预览' }}</h3>
          <span>{{ previewSubtitle }}</span>
        </div>
        <div class="artifact-preview-dialog-actions">
          <button
            v-if="selectedArtifact"
            type="button"
            class="panel-text-button"
            :disabled="downloading"
            @click="emit('download', selectedArtifact)"
          >
            下载
          </button>
          <button type="button" class="panel-icon-button" title="关闭" aria-label="关闭" @click="emit('close-preview')">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
      </header>

      <div class="artifact-preview-dialog-body">
        <div v-if="previewLoading" class="artifact-preview-state">正在读取预览...</div>
        <div v-else-if="!selectedArtifact" class="artifact-preview-state">请选择一个文件</div>
        <div v-else-if="!preview" class="artifact-preview-state">正在准备文件预览</div>
        <div v-else-if="previewMode === 'code'" class="artifact-code-preview">
          <div class="artifact-preview-toolbar">
            <span>{{ artifactPreviewTitle(previewMode) }}</span>
            <small v-if="language">{{ language }}</small>
          </div>
          <pre><code class="hljs" :class="language ? `language-${language}` : ''" v-html="highlightedHtml" /></pre>
          <span v-if="preview.truncated" class="artifact-preview-note">预览已截断</span>
        </div>
        <div v-else-if="previewMode === 'text'" class="artifact-text-preview">
          <pre>{{ preview.content }}</pre>
          <span v-if="preview.truncated" class="artifact-preview-note">预览已截断</span>
        </div>
        <div v-else class="artifact-preview-state">
          <strong>暂不支持预览该文件</strong>
          <span>当前可以直接下载，后续会在这里接入 Word、PowerPoint、Excel 等文件预览。</span>
        </div>
      </div>
    </section>
  </aside>
</template>

<script setup lang="ts">
import 'highlight.js/styles/github.css'

import type { ChatArtifactContentRecord } from '../../api/chat'
import { artifactPreviewTitle } from '../artifact-preview.helpers'
import type { ArtifactPreviewMode } from '../artifact-preview.helpers'
import type { ChatArtifactItem } from './chat-types'
import { formatFileSize } from './useChatArtifacts'

defineProps<{
  workPanelOpen: boolean
  previewOpen: boolean
  artifacts: ChatArtifactItem[]
  selectedArtifact: ChatArtifactItem | null
  loading: boolean
  previewLoading: boolean
  downloading: boolean
  preview: ChatArtifactContentRecord | null
  previewMode: ArtifactPreviewMode
  previewSubtitle: string
  language: string
  highlightedHtml: string
}>()

const emit = defineEmits<{
  'close-panel': []
  'close-preview': []
  select: [artifact: ChatArtifactItem]
  download: [artifact: ChatArtifactItem]
  'download-all': []
}>()
</script>

<style scoped src="./chat-controls.css"></style>

<style scoped>
.work-panel,
.artifact-preview-panel {
  display: grid;
  height: 100vh;
  min-width: 0;
  min-height: 0;
  padding: 14px;
  border-left: 1px solid #ececf1;
  background: #fbfbfb;
  animation: side-panel-enter 0.18s ease-out;
}

.work-panel {
  align-content: start;
  gap: 12px;
  overflow-y: auto;
}

.artifact-preview-panel {
  align-content: stretch;
  background: #f8fafc;
  overflow: hidden;
}

@keyframes side-panel-enter {
  from {
    opacity: 0;
    transform: translateX(16px);
  }

  to {
    opacity: 1;
    transform: translateX(0);
  }
}

.work-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 2px;
}

.work-panel-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.panel-icon-button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 8px;
  color: #374151;
}

.panel-icon-button:hover:not(:disabled),
.artifact-item-actions button:hover:not(:disabled),
.panel-text-button:hover:not(:disabled) {
  background: #f1f2f4;
}

.panel-icon-button:disabled,
.artifact-item-actions button:disabled,
.panel-text-button:disabled {
  color: #c0c4cc;
  cursor: not-allowed;
}

.panel-icon-button svg,
.artifact-item-actions svg {
  width: 17px;
  height: 17px;
}

.work-panel-header strong,
.work-panel-header span {
  display: block;
}

.work-panel-header strong {
  color: #111827;
  font-size: 14px;
}

.work-panel-header span {
  color: #6b7280;
  font-size: 12px;
}

.artifact-panel-section {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.artifact-list {
  display: grid;
  gap: 8px;
}

.artifact-list-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  min-width: 0;
  border: 1px solid transparent;
  border-radius: 8px;
  background: #ffffff;
}

.artifact-list-item.active {
  border-color: #d6dae2;
  background: #f8fafc;
}

.artifact-preview-trigger {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 8px;
  text-align: left;
}

.artifact-file-icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #6b7280;
}

.artifact-file-icon svg {
  width: 26px;
  height: 26px;
}

.artifact-file-meta {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.artifact-file-meta strong {
  overflow: hidden;
  color: #111827;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-file-meta small {
  color: #6b7280;
  font-size: 11px;
}

.artifact-item-actions {
  display: inline-flex;
  padding-right: 4px;
}

.artifact-item-actions button {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 8px;
  color: #374151;
}

.panel-text-button {
  flex: 0 0 auto;
  padding: 6px 9px;
  border-radius: 8px;
  color: #374151;
  font-size: 12px;
}

.artifact-empty {
  color: #6b7280;
  font-size: 12px;
}

.artifact-empty {
  display: grid;
  min-height: 96px;
  place-items: center;
  border: 1px dashed #d9dce3;
  border-radius: 8px;
  background: #fafafa;
  text-align: center;
}

.artifact-preview-dialog {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  width: 100%;
  height: 100%;
  min-width: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  overflow: hidden;
  box-shadow: none;
}

.artifact-preview-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
  padding: 14px 16px;
  border-bottom: 1px solid #ececf1;
}

.artifact-preview-dialog-header h3 {
  overflow: hidden;
  margin: 0;
  color: #111827;
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-preview-dialog-header span {
  display: block;
  margin-top: 2px;
  color: #6b7280;
  font-size: 12px;
}

.artifact-preview-dialog-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
}

.artifact-preview-dialog-body {
  min-height: 0;
  min-width: 0;
  padding: 14px;
  background: #f8fafc;
  overflow: hidden;
}

.artifact-code-preview,
.artifact-text-preview {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 8px;
  height: 100%;
  min-width: 0;
}

.artifact-preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: #374151;
  font-size: 12px;
}

.artifact-preview-toolbar small {
  color: #6b7280;
  font-size: 12px;
}

.artifact-code-preview pre,
.artifact-text-preview pre {
  overflow: auto;
  min-height: 0;
  margin: 0;
  padding: 14px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #ffffff;
  color: #1f2937;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 13px;
  line-height: 1.65;
}

.artifact-code-preview code {
  display: block;
  min-width: max-content;
  padding: 0;
  background: transparent;
}

.artifact-text-preview pre {
  white-space: pre-wrap;
  word-break: break-word;
}

.artifact-preview-note {
  color: #6b7280;
  font-size: 12px;
}

.artifact-preview-state {
  display: grid;
  gap: 6px;
  height: 100%;
  min-height: 240px;
  place-content: center;
  justify-items: center;
  border: 1px dashed #d9dce3;
  border-radius: 8px;
  background: #ffffff;
  color: #6b7280;
  font-size: 13px;
  text-align: center;
}

.artifact-preview-state strong {
  color: #111827;
  font-size: 15px;
}

@media (max-width: 1020px) {
  .work-panel,
  .artifact-preview-panel {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    z-index: 30;
    width: min(340px, calc(100vw - 32px));
    height: 100vh;
    border-left: 1px solid #ececf1;
    box-shadow: -18px 0 46px rgba(15, 23, 42, 0.14);
  }

  .artifact-preview-panel {
    z-index: 31;
    width: min(620px, calc(100vw - 32px));
  }
}

@media (max-width: 640px) {
  .work-panel {
    width: min(360px, calc(100vw - 16px));
  }

  .artifact-preview-panel {
    width: min(560px, calc(100vw - 16px));
  }
}
</style>
