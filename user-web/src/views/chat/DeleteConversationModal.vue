<template>
  <n-modal
    v-model:show="show"
    to="body"
    transform-origin="center"
    :mask-closable="!deleting"
    :close-on-esc="!deleting"
  >
    <section class="delete-conversation-modal" role="dialog" aria-modal="true" aria-labelledby="delete-conversation-title">
      <header class="delete-modal-header">
        <h3 id="delete-conversation-title">删除会话</h3>
      </header>
      <div class="delete-modal-body">
        <p>删除后无法恢复，确认删除这个会话吗？</p>
      </div>
      <div class="delete-modal-footer">
        <n-button :disabled="deleting" @click="emit('cancel')">取消</n-button>
        <n-button type="error" :loading="deleting" @click="emit('confirm')">删除</n-button>
      </div>
    </section>
  </n-modal>
</template>

<script setup lang="ts">
import { NButton, NModal } from 'naive-ui'

const show = defineModel<boolean>('show', { required: true })

defineProps<{
  deleting: boolean
}>()

const emit = defineEmits<{
  cancel: []
  confirm: []
}>()
</script>

<style scoped>
.delete-conversation-modal {
  display: grid;
  width: min(320px, calc(100vw - 32px));
  padding: 0;
  border-radius: 8px;
  background: #ffffff;
  margin: auto;
  overflow: hidden;
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.16);
}

.delete-modal-header {
  padding: 18px 20px 8px;
}

.delete-conversation-modal h3 {
  margin: 0;
  color: #111827;
  font-size: 16px;
  font-weight: 600;
}

.delete-modal-body {
  padding: 8px 20px 18px;
}

.delete-conversation-modal p {
  margin: 0;
  color: #4b5563;
  font-size: 14px;
  line-height: 1.7;
}

.delete-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 20px;
  border-top: 1px solid #eef0f3;
  background: #fafafa;
}
</style>
