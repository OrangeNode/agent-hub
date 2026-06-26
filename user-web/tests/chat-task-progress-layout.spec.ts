import { describe, expect, it } from 'vitest'
import chatViewSource from '../src/views/ChatView.vue?raw'
import taskProgressPanelSource from '../src/views/TaskProgressPanel.vue?raw'

describe('聊天任务进度布局', () => {
  it('聊天页应集成任务进度面板和活跃任务恢复', () => {
    expect(chatViewSource).toContain("import TaskProgressPanel from './TaskProgressPanel.vue'")
    expect(chatViewSource).toContain('activeTaskProgress')
    expect(chatViewSource).toContain('applyTaskProgress')
    expect(chatViewSource).toContain('onTaskProgressStarted')
    expect(chatViewSource).toContain('onTaskProgressCompleted')
    expect(chatViewSource).toContain('fetchActiveTaskProgress')
  })

  it('任务进度面板应提供折叠摘要和展开列表', () => {
    expect(taskProgressPanelSource).toContain('{{ summaryText }}')
    expect(taskProgressPanelSource).toContain('任务名称')
    expect(taskProgressPanelSource).toContain('任务进度')
    expect(taskProgressPanelSource).toContain('task-progress-spinner')
    expect(taskProgressPanelSource).toContain('task-progress-dot completed')
  })

  it('任务进度面板应放在空状态和会话输入区域上方', () => {
    const panelMarkup = '<TaskProgressPanel v-if="activeTaskProgress" :progress="activeTaskProgress" />'
    const landingPanelIndex = chatViewSource.indexOf(panelMarkup)
    const landingComposerIndex = chatViewSource.indexOf('<div class="landing-composer"')
    const footerIndex = chatViewSource.indexOf('<footer class="conversation-composer">')
    const conversationPanelIndex = chatViewSource.indexOf(panelMarkup, footerIndex)
    const pendingImagesIndex = chatViewSource.indexOf('v-if="composerImageCount"', footerIndex)
    const compactComposerIndex = chatViewSource.indexOf('<div class="landing-composer compact"', footerIndex)

    expect(landingPanelIndex).toBeGreaterThan(-1)
    expect(landingPanelIndex).toBeLessThan(landingComposerIndex)
    expect(conversationPanelIndex).toBeGreaterThan(footerIndex)
    expect(conversationPanelIndex).toBeLessThan(pendingImagesIndex)
    expect(conversationPanelIndex).toBeLessThan(compactComposerIndex)
  })
})
