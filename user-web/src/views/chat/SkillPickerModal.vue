<template>
  <n-modal v-model:show="show" to="body" transform-origin="center">
    <section class="skill-picker-modal" role="dialog" aria-modal="true" aria-labelledby="skill-picker-title">
      <header class="skill-picker-header">
        <div>
          <h3 id="skill-picker-title">选择 Skill</h3>
          <span>已选 {{ selectedIds.length }} 个</span>
        </div>
        <n-button quaternary size="small" @click="show = false">完成</n-button>
      </header>
      <div class="skill-picker-list" :class="{ loading }">
        <p v-if="loading" class="skill-picker-empty">正在加载 Skill...</p>
        <p v-else-if="!skills.length" class="skill-picker-empty">暂无可用 Skill</p>
        <label
          v-for="skill in skills"
          v-else
          :key="skill.skillId"
          class="skill-picker-option"
        >
          <input
            type="checkbox"
            :checked="selectedIds.includes(skill.skillId)"
            @change="emit('toggle', skill.skillId)"
          />
          <span>
            <strong>{{ skill.name }}</strong>
            <small>{{ skill.description || skill.skillKey }}</small>
          </span>
        </label>
      </div>
    </section>
  </n-modal>
</template>

<script setup lang="ts">
import { NButton, NModal } from 'naive-ui'

import type { AvailableSkillRecord } from '../../api/chat'

const show = defineModel<boolean>('show', { required: true })

defineProps<{
  skills: AvailableSkillRecord[]
  selectedIds: number[]
  loading: boolean
}>()

const emit = defineEmits<{
  toggle: [skillId: number]
}>()
</script>

<style scoped>
.skill-picker-modal {
  display: grid;
  gap: 14px;
  width: min(480px, calc(100vw - 32px));
  padding: 18px;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.16);
}

.skill-picker-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.skill-picker-header h3 {
  margin: 0 0 4px;
  color: #111827;
  font-size: 16px;
  font-weight: 600;
}

.skill-picker-header span {
  color: #6b7280;
  font-size: 12px;
}

.skill-picker-list {
  display: grid;
  gap: 8px;
  max-height: min(320px, 52vh);
  overflow-y: auto;
}

.skill-picker-list.loading {
  min-height: 72px;
}

.skill-picker-empty {
  margin: 0;
  padding: 16px 0;
  color: #6b7280;
  font-size: 14px;
  text-align: center;
}

.skill-picker-option {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  align-items: flex-start;
  gap: 10px;
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  cursor: pointer;
}

.skill-picker-option:hover {
  background: #f8fafc;
}

.skill-picker-option input {
  width: 16px;
  height: 16px;
  margin: 2px 0 0;
  accent-color: #2563eb;
}

.skill-picker-option span {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.skill-picker-option strong {
  overflow: hidden;
  color: #111827;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-picker-option small {
  overflow: hidden;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
