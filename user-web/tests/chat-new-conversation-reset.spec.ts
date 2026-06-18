import { describe, expect, it } from 'vitest'
import { resolveNewConversationState } from '../src/views/ChatView.vue'
import chatViewSource from '../src/views/ChatView.vue?raw'

const normalizedChatViewSource = chatViewSource.replace(/\r\n/g, '\n')

function functionBlock(name: string) {
  const start = normalizedChatViewSource.indexOf(`function ${name}`)
  if (start < 0) {
    return ''
  }

  const nextFunction = normalizedChatViewSource.indexOf('\nfunction ', start + 1)
  return normalizedChatViewSource.slice(start, nextFunction < 0 ? undefined : nextFunction)
}

describe('新聊天状态重置', () => {
  it('点击新聊天应恢复到空聊天初始状态', () => {
    expect(resolveNewConversationState({
      activeConversationId: 12,
      activeAgentKey: 'writer',
      activeAgentName: '写作 Agent',
      selectedSkillIds: [1, 2],
      prompt: '旧输入',
      workPanelOpen: true,
      artifactPreviewOpen: true,
      artifactPreview: { content: 'preview' },
      selectedArtifactId: 9,
      skillPickerOpen: true,
      historyImagePickerOpen: true,
      thinkingConfigOpen: true,
      openConversationMenuId: 12,
      agentMenuOpen: true
    })).toEqual({
      activeConversationId: null,
      activeAgentKey: null,
      activeAgentName: '通用 Agent',
      selectedSkillIds: [],
      prompt: '',
      workPanelOpen: false,
      artifactPreviewOpen: false,
      artifactPreview: null,
      selectedArtifactId: null,
      skillPickerOpen: false,
      historyImagePickerOpen: false,
      thinkingConfigOpen: false,
      openConversationMenuId: null,
      agentMenuOpen: false
    })
  })

  it('Agent 入口创建新聊天时应保留刚选择的 Agent', () => {
    const nextState = resolveNewConversationState({
      activeConversationId: 12,
      activeAgentKey: 'coder',
      activeAgentName: '代码 Agent',
      selectedSkillIds: [1],
      prompt: '旧输入',
      workPanelOpen: true,
      artifactPreviewOpen: true,
      artifactPreview: null,
      selectedArtifactId: 3,
      skillPickerOpen: true,
      historyImagePickerOpen: true,
      thinkingConfigOpen: true,
      openConversationMenuId: 12,
      agentMenuOpen: true
    }, { preserveAgent: true })

    expect(nextState.activeAgentKey).toBe('coder')
    expect(nextState.activeAgentName).toBe('代码 Agent')
    expect(nextState.activeConversationId).toBeNull()
    expect(nextState.selectedSkillIds).toEqual([])
  })

  it('createConversation 应中断旧流并清空 Skill 状态', () => {
    const createConversation = functionBlock('createConversation')

    expect(createConversation).toContain('markCurrentAssistantStopped()')
    expect(createConversation).toContain('closeStream()')
    expect(createConversation).toContain('selectedSkillIds.value = nextState.selectedSkillIds')
    expect(createConversation).not.toContain('if (activeConversationId.value === null) {\n    return\n  }')
  })
})
