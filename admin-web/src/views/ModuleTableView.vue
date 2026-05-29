<template>
  <div class="module-page">
    <n-card :bordered="false" class="module-header">
      <div class="module-header__content">
        <div>
          <span class="section">{{ moduleDefinition?.section ?? '模块' }}</span>
          <h2>{{ moduleDefinition?.title ?? '模块页面' }}</h2>
          <p>{{ moduleDefinition?.description ?? '用于展示当前模块的初始化占位数据。' }}</p>
        </div>

        <div class="module-header__actions">
          <n-input v-model:value="keyword" clearable placeholder="按名称或编码筛选" />
          <n-button type="primary">新建条目</n-button>
        </div>
      </div>
    </n-card>

    <n-card size="small">
      <n-data-table
        :columns="columns"
        :data="filteredRows"
        :pagination="{ pageSize: 6 }"
        :bordered="false"
      />
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { useRoute } from 'vue-router'
import type { DataTableColumns } from 'naive-ui'
import { NButton, NCard, NDataTable, NInput, NTag } from 'naive-ui'
import { getModuleDefinition, type ModuleRow } from '../config/module-routes'

const route = useRoute()
const keyword = ref('')

const moduleDefinition = computed(() => getModuleDefinition(route.name))

const columns: DataTableColumns<ModuleRow> = [
  { title: '名称', key: 'name' },
  { title: '编码', key: 'code' },
  { title: '归属', key: 'owner' },
  {
    title: '状态',
    key: 'status',
    render: (row) => {
      const type = row.status === '启用' ? 'success' : row.status === '草稿' ? 'warning' : 'default'
      return h(NTag, { type, bordered: false }, { default: () => row.status })
    }
  },
  { title: '最近更新', key: 'updatedAt' }
]

const filteredRows = computed(() => {
  const rows = moduleDefinition.value?.rows ?? []
  const currentKeyword = keyword.value.trim().toLowerCase()

  if (!currentKeyword) {
    return rows
  }

  return rows.filter((item) =>
    [item.name, item.code, item.owner].some((value) => value.toLowerCase().includes(currentKeyword))
  )
})
</script>

<style scoped>
.module-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.module-header {
  border-radius: 8px;
}

.module-header__content {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.module-header__content h2 {
  margin: 8px 0;
  font-size: 24px;
}

.module-header__content p {
  margin: 0;
  color: #64748b;
}

.section {
  color: #1d4ed8;
  font-size: 13px;
  font-weight: 600;
}

.module-header__actions {
  display: grid;
  gap: 12px;
  width: min(320px, 100%);
}

@media (max-width: 840px) {
  .module-header__content {
    flex-direction: column;
  }

  .module-header__actions {
    width: 100%;
  }
}
</style>
