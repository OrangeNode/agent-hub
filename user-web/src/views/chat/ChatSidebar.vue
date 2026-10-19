<template>
  <aside class="sidebar">
    <div class="sidebar-header">
      <strong>Agent Hub</strong>
      <button type="button" class="icon-button" title="折叠侧栏" aria-label="折叠侧栏" @click="emit('collapse')">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 5h14v14H5z" />
          <path d="M9 5v14" />
        </svg>
      </button>
    </div>

    <nav class="primary-nav" aria-label="主导航">
      <button type="button" class="nav-item" @click="emit('create')">
        <span class="nav-icon">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </span>
        新聊天
      </button>

      <div
        class="agent-menu-wrap"
        :class="{ open: agentMenuOpen }"
        @mouseenter="agentMenuOpen = true"
        @mouseleave="agentMenuOpen = false"
      >
        <button type="button" class="nav-item agent-trigger" @click="agentMenuOpen = !agentMenuOpen">
          <span class="nav-icon">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M7 8h10M7 12h10M7 16h6" />
              <path d="M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" />
            </svg>
          </span>
          更多 Agent
          <span class="chevron">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="m9 6 6 6-6 6" />
            </svg>
          </span>
        </button>

        <div class="agent-menu" role="menu">
          <button
            v-for="agent in agentOptions"
            :key="agent.key"
            type="button"
            class="agent-option"
            role="menuitem"
            @click="selectAgent(agent)"
          >
            <span class="agent-avatar">{{ agent.shortName }}</span>
            <span>
              <strong>{{ agent.name }}</strong>
              <small>{{ agent.description }}</small>
            </span>
          </button>
        </div>
      </div>
    </nav>

    <section class="recent-section" @scroll="handleConversationListScroll">
      <template v-for="group in groupedConversations" :key="group.key">
        <h2 class="recent-group-title">{{ group.title }}</h2>
        <div
          v-for="conversation in group.items"
          :key="conversation.id"
          class="recent-item"
          :class="{ active: conversation.id === activeConversationId }"
        >
          <button type="button" class="recent-title" @click="switchConversation(conversation.id)">
            {{ conversation.title }}
          </button>
          <div class="recent-actions">
            <button
              type="button"
              class="recent-more"
              aria-label="更多操作"
              @click.stop="toggleConversationMenu(conversation.id, $event)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 12h.01M19 12h.01M5 12h.01" />
              </svg>
            </button>
          </div>
        </div>
      </template>
      <div v-if="conversationsLoading" class="recent-load-state">加载中...</div>
      <div v-else-if="!hasConversations" class="recent-load-state">暂无会话</div>
    </section>

    <div class="sidebar-footer" @click.stop>
      <button
        type="button"
        class="account-trigger"
        :class="{ open: accountMenuOpen }"
        aria-haspopup="menu"
        :aria-expanded="accountMenuOpen"
        @click="toggleAccountMenu"
      >
        <span class="user-avatar">{{ userInitial }}</span>
        <span class="user-meta">
          <span class="user-name">{{ displayName }}</span>
          <span class="account-plan">Agent Hub</span>
        </span>
        <span class="account-footer-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path d="M7 8h10M7 12h10M7 16h10" />
            <path d="M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" />
          </svg>
        </span>
      </button>

      <div v-if="accountMenuOpen" class="account-menu" role="menu" @click.stop>
        <div class="account-menu-header">
          <span class="user-avatar">{{ userInitial }}</span>
          <span class="user-meta">
            <span class="user-name">{{ displayName }}</span>
            <span class="account-plan">Agent Hub</span>
          </span>
          <span class="account-menu-arrow" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="m9 6 6 6-6 6" />
            </svg>
          </span>
        </div>
        <div class="account-menu-divider" />
        <button type="button" class="account-menu-item danger" role="menuitem" @click="emit('logout')">
          <span class="account-menu-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M10 17l5-5-5-5" />
              <path d="M15 12H3" />
              <path d="M21 19V5a2 2 0 0 0-2-2h-6" />
            </svg>
          </span>
          <span>退出登录</span>
        </button>
      </div>
    </div>

    <!-- 固定定位的会话操作菜单，放在侧栏内保证组件单根，便于父组件按 .sidebar 控制折叠。 -->
    <div
      v-if="activeMenuConversation"
      class="conversation-action-menu"
      role="menu"
      :style="{ top: `${conversationMenuPosition.top}px`, left: `${conversationMenuPosition.left}px` }"
      @click.stop
    >
      <button type="button" role="menuitem" @click.stop="renameConversation(activeMenuConversation)">重命名</button>
      <button type="button" role="menuitem" class="danger" @click.stop="deleteConversation(activeMenuConversation.id)">
        删除
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

import type { ConversationGroup, ConversationItem } from '../chat-agent.helpers'
import type { AgentOption } from './chat-types'

const props = defineProps<{
  groupedConversations: ConversationGroup[]
  activeConversationId: number | null
  conversationsLoading: boolean
  hasConversations: boolean
  agentOptions: AgentOption[]
  displayName: string
  userInitial: string
}>()

const emit = defineEmits<{
  collapse: []
  create: []
  'select-agent': [agent: AgentOption]
  switch: [conversationId: number]
  rename: [conversation: ConversationItem]
  delete: [conversationId: number]
  logout: []
  'load-more': []
}>()

const agentMenuOpen = ref(false)
const accountMenuOpen = ref(false)
const openConversationMenuId = ref<number | null>(null)
const conversationMenuPosition = ref({ top: 0, left: 0 })

const activeMenuConversation = computed(() =>
  props.groupedConversations
    .flatMap((group) => group.items)
    .find((item) => item.id === openConversationMenuId.value) ?? null
)

function closeMenus() {
  openConversationMenuId.value = null
  accountMenuOpen.value = false
  agentMenuOpen.value = false
}

function selectAgent(agent: AgentOption) {
  agentMenuOpen.value = false
  emit('select-agent', agent)
}

function switchConversation(conversationId: number) {
  closeMenus()
  emit('switch', conversationId)
}

function renameConversation(conversation: ConversationItem) {
  closeMenus()
  emit('rename', conversation)
}

function deleteConversation(conversationId: number) {
  closeMenus()
  emit('delete', conversationId)
}

function toggleConversationMenu(conversationId: number, event: MouseEvent) {
  if (openConversationMenuId.value === conversationId) {
    openConversationMenuId.value = null
    return
  }

  accountMenuOpen.value = false
  const trigger = event.currentTarget as HTMLElement
  const rect = trigger.getBoundingClientRect()
  const menuWidth = 118
  const gap = 6
  const viewportPadding = 8
  const rightSideLeft = rect.right + gap
  const left = rightSideLeft + menuWidth > window.innerWidth - viewportPadding
    ? Math.max(viewportPadding, rect.left - menuWidth - gap)
    : rightSideLeft

  conversationMenuPosition.value = {
    top: Math.min(rect.top, window.innerHeight - 88),
    left
  }
  openConversationMenuId.value = conversationId
}

function toggleAccountMenu() {
  openConversationMenuId.value = null
  accountMenuOpen.value = !accountMenuOpen.value
}

function handleConversationListScroll(event: Event) {
  const target = event.currentTarget as HTMLElement | null
  if (!target) {
    return
  }

  if (target.scrollTop + target.clientHeight >= target.scrollHeight - 48) {
    emit('load-more')
  }
}

defineExpose({ closeMenus })
</script>

<style scoped src="./chat-controls.css"></style>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding: 12px 8px;
  background: #f7f8fa;
  border-right: 1px solid #e8eaed;
  min-width: 0;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 8px 16px;
}

.sidebar-header strong {
  font-size: 20px;
  letter-spacing: -0.03em;
  color: #1a1a2e;
}

.icon-button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 8px;
  color: #6b7280;
}

.icon-button:hover {
  background: #e8eaed;
}

.primary-nav {
  display: grid;
  gap: 4px;
  position: relative;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 38px;
  width: 100%;
  padding: 8px 10px;
  border-radius: 10px;
  color: #444;
  text-align: left;
  font-weight: 500;
}

.nav-item.active,
.nav-item:hover,
.recent-item.active,
.recent-item:hover {
  background: #e8eaed;
  color: #1a1a2e;
}

.nav-icon {
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  color: #555;
}

.chevron {
  display: grid;
  margin-left: auto;
  color: #6b7280;
}

.chevron svg {
  width: 15px;
  height: 15px;
}

.agent-menu-wrap {
  position: relative;
}

.agent-menu {
  position: absolute;
  z-index: 20;
  top: 0;
  left: calc(100% + 8px);
  display: grid;
  width: 286px;
  gap: 4px;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.14);
  opacity: 0;
  pointer-events: none;
  transform: translateX(-6px);
  transition: opacity 0.14s ease, transform 0.14s ease;
}

.agent-menu-wrap:hover .agent-menu,
.agent-menu-wrap:focus-within .agent-menu,
.agent-menu-wrap.open .agent-menu {
  opacity: 1;
  pointer-events: auto;
  transform: translateX(0);
}

.agent-option {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 10px;
  border-radius: 12px;
  text-align: left;
}

.agent-option:hover {
  background: #f3f4f6;
}

.agent-avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  background: #eff6ff;
  color: #2563eb;
  font-weight: 700;
}

.agent-option strong,
.agent-option small {
  display: block;
}

.agent-option strong {
  color: #1a1a2e;
  font-size: 14px;
}

.agent-option small {
  margin-top: 3px;
  color: #6b7280;
  font-size: 12px;
}

.recent-section {
  flex: 1;
  overflow-y: auto;
  overflow-x: visible;
  margin-top: 24px;
  padding: 0 4px;
}

.recent-section h2 {
  margin: 0 4px 8px;
  color: #999;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.recent-group-title:not(:first-child) {
  margin-top: 18px;
}

.recent-load-state {
  padding: 10px 8px;
  color: #8a8f98;
  font-size: 12px;
}

.recent-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  overflow: visible;
  padding: 2px 4px 2px 8px;
  border-radius: 10px;
  color: #555;
  font-size: 14px;
}

.recent-title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  padding: 7px 0;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-actions {
  position: relative;
  opacity: 0;
  pointer-events: none;
}

.recent-item:hover .recent-actions,
.recent-item.active .recent-actions,
.recent-actions:focus-within {
  opacity: 1;
  pointer-events: auto;
}

.recent-more {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 8px;
  color: #6b7280;
}

.recent-more:hover {
  background: #dfe3e8;
}

.conversation-action-menu {
  position: fixed;
  z-index: 1000;
  display: grid;
  width: 118px;
  gap: 2px;
  padding: 6px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 14px 36px rgba(15, 23, 42, 0.14);
}

.conversation-action-menu button {
  padding: 7px 8px;
  border-radius: 6px;
  text-align: left;
  font-size: 13px;
}

.conversation-action-menu button:hover {
  background: #f3f4f6;
}

.conversation-action-menu .danger {
  color: #dc2626;
}

.sidebar-footer {
  position: relative;
  display: flex;
  align-items: center;
  padding: 8px 0 0;
  border-top: 1px solid #e4e4e7;
}

.account-trigger {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr) 22px;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-width: 0;
  min-height: 52px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: #111827;
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: background-color 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease;
}

.account-trigger:hover,
.account-trigger.open {
  border-color: transparent;
  background: #ececec;
  box-shadow: none;
}

.account-trigger:focus-visible {
  outline: 2px solid #d1d5db;
  outline-offset: 2px;
}

.user-avatar {
  display: grid;
  width: 26px;
  height: 26px;
  place-items: center;
  border-radius: 999px;
  background: #f2a13b;
  color: #ffffff;
  font-size: 11px;
  font-weight: 500;
}

.user-meta {
  display: grid;
  gap: 1px;
  min-width: 0;
}

.user-name {
  display: block;
  overflow: hidden;
  font-size: 14px;
  font-weight: 400;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-plan {
  display: block;
  overflow: hidden;
  color: #8a8a8a;
  font-size: 12px;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-footer-icon,
.account-menu-arrow,
.account-menu-icon {
  display: grid;
  width: 22px;
  height: 22px;
  flex: 0 0 auto;
  place-items: center;
  color: #6f6f6f;
}

.account-footer-icon svg,
.account-menu-arrow svg,
.account-menu-icon svg {
  width: 17px;
  height: 17px;
}

.account-menu {
  position: absolute;
  right: 0;
  bottom: calc(100% + 6px);
  left: 0;
  z-index: 20;
  display: grid;
  gap: 4px;
  padding: 8px;
  border: 1px solid #dddddd;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.16);
}

.account-menu-header {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr) 22px;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  padding: 6px 8px;
}

.account-menu-divider {
  height: 1px;
  margin: 4px 8px;
  background: #ececec;
}

.account-menu-item {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 36px;
  padding: 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #202123;
  cursor: pointer;
  font: inherit;
  font-size: 14px;
  text-align: left;
}

.account-menu-item:hover {
  background: #f3f4f6;
}

.account-menu-item.danger {
  color: #202123;
}

.recent-section {
  scrollbar-width: thin;
  scrollbar-color: #cfd4dc transparent;
}

.recent-section::-webkit-scrollbar {
  width: 8px;
}

.recent-section::-webkit-scrollbar-track {
  background: transparent;
}

.recent-section::-webkit-scrollbar-thumb {
  border: 2px solid transparent;
  border-radius: 999px;
  background: #cfd4dc;
  background-clip: content-box;
}

.recent-section::-webkit-scrollbar-thumb:hover {
  background: #c6ccd6;
  background-clip: content-box;
}

@media (max-width: 640px) {
  .sidebar {
    min-height: auto;
    border-right: 0;
    border-bottom: 1px solid #ececf1;
  }

  .recent-section {
    max-height: 180px;
  }

  .agent-menu {
    top: calc(100% + 8px);
    left: 0;
    width: min(86vw, 286px);
  }
}
</style>
