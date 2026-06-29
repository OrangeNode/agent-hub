import { describe, expect, it } from 'vitest'
import {
  markTaskProgressTerminal,
  shouldApplyTaskProgressForStream
} from '../src/views/ChatView.vue'
import chatViewSource from '../src/views/ChatView.vue?raw'
import taskProgressPanelSource from '../src/views/TaskProgressPanel.vue?raw'

const normalizedChatViewSource = chatViewSource.replace(/\r\n/g, '\n')

function functionBlock(name: string) {
  const start = normalizedChatViewSource.indexOf(`function ${name}`)
  if (start < 0) {
    return ''
  }

  const nextFunction = normalizedChatViewSource.indexOf('\nfunction ', start + 1)
  return normalizedChatViewSource.slice(start, nextFunction < 0 ? undefined : nextFunction)
}

describe('聊天任务进度布局', () => {
  it('聊天页应集成任务进度面板和活跃任务恢复', () => {
    expect(chatViewSource).toContain("import TaskProgressPanel from './TaskProgressPanel.vue'")
    expect(chatViewSource).toContain('activeTaskProgress')
    expect(chatViewSource).toContain('applyTaskProgress')
    expect(chatViewSource).toContain('onTaskProgressStarted')
    expect(chatViewSource).toContain('onTaskProgressCompleted')
    expect(chatViewSource).toContain('fetchLatestTaskProgress')
  })

  it('任务进度面板应提供折叠进度胶囊和展开时间线列表', () => {
    expect(taskProgressPanelSource).toContain('{{ summaryText }}')
    expect(taskProgressPanelSource).toContain('task-progress-state')
    expect(taskProgressPanelSource).toContain('task-progress-meter')
    expect(taskProgressPanelSource).toContain('task-progress-subtitle')
    expect(taskProgressPanelSource).toContain('task-progress-timeline')
    expect(taskProgressPanelSource).toContain('task-progress-step')
    expect(taskProgressPanelSource).toContain('task-progress-badge')
    expect(taskProgressPanelSource).toContain('task-progress-spinner')
    expect(taskProgressPanelSource).not.toContain('task-progress-head')
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

  it('旧 stream token 或已切换会话时不应应用任务进度', () => {
    expect(shouldApplyTaskProgressForStream({
      currentTaskStreamToken: 2,
      streamToken: 2,
      activeConversationId: 100,
      streamConversationIds: [-1, 100]
    })).toBe(true)

    expect(shouldApplyTaskProgressForStream({
      currentTaskStreamToken: 3,
      streamToken: 2,
      activeConversationId: 100,
      streamConversationIds: [-1, 100]
    })).toBe(false)

    expect(shouldApplyTaskProgressForStream({
      currentTaskStreamToken: 2,
      streamToken: 2,
      activeConversationId: 101,
      streamConversationIds: [-1, 100]
    })).toBe(false)
  })

  it('本地终止任务进度时应保留任务内容并同步运行中步骤状态', () => {
    const progress = {
      taskRunId: 12,
      mode: 'AUTO',
      status: 'RUNNING',
      title: '生成报告',
      currentStepIndex: 2,
      totalSteps: 3,
      revision: 1,
      errorMessage: null,
      steps: [
        { stepIndex: 1, name: '检索资料', status: 'COMPLETED', errorMessage: null },
        { stepIndex: 2, name: '撰写报告', status: 'RUNNING', errorMessage: null },
        { stepIndex: 3, name: '整理引用', status: 'PENDING', errorMessage: null }
      ]
    }

    const failed = markTaskProgressTerminal(progress, 'FAILED', '工具调用失败')
    const stopped = markTaskProgressTerminal(progress, 'STOPPED', '已中止生成')

    expect(failed?.status).toBe('FAILED')
    expect(failed?.errorMessage).toBe('工具调用失败')
    expect(failed?.steps[0].status).toBe('COMPLETED')
    expect(failed?.steps[1]).toMatchObject({ name: '撰写报告', status: 'FAILED', errorMessage: '工具调用失败' })
    expect(failed?.steps[2].status).toBe('PENDING')
    expect(stopped?.status).toBe('STOPPED')
    expect(stopped?.steps[1]).toMatchObject({ name: '撰写报告', status: 'STOPPED', errorMessage: '已中止生成' })
    expect(progress.steps[1].status).toBe('RUNNING')
  })

  it('聊天页应通过受保护的 stream 任务处理器更新进度', () => {
    const handleSend = functionBlock('handleSend')

    expect(normalizedChatViewSource).toContain('currentTaskStreamToken')
    expect(normalizedChatViewSource).toContain('guardedApplyTaskProgress')
    expect(handleSend).toContain('const taskStreamToken = activateTaskStreamContext(conversationId)')
    expect(handleSend).toContain('onTaskProgressStarted: guardedApplyTaskProgress')
    expect(handleSend).toContain('onTaskProgressCompleted: guardedApplyTaskProgress')
  })

  it('任务错误和中止路径应同步本地终态，避免进度长期停留进行中', () => {
    const stopCurrentStream = functionBlock('stopCurrentStream')
    const onErrorIndex = normalizedChatViewSource.indexOf('onError: (data) => {')
    const onDoneIndex = normalizedChatViewSource.indexOf('onDone: () => {')
    const onTransportErrorIndex = normalizedChatViewSource.indexOf('onTransportError: () => {')
    const stopMarkIndex = stopCurrentStream.indexOf('markTaskProgressStoppedForCurrentStream(\'已中止生成\')')
    const stopDeactivateIndex = stopCurrentStream.indexOf('deactivateTaskStreamContext()', stopMarkIndex)

    expect(stopCurrentStream).toContain('markTaskProgressStoppedForCurrentStream(\'已中止生成\')')
    expect(stopDeactivateIndex).toBeGreaterThan(stopMarkIndex)
    expect(normalizedChatViewSource.slice(onErrorIndex, onDoneIndex))
      .toContain('markTaskProgressFailedForCurrentStream(data.message ?? \'流式响应失败\', taskStreamToken)')
    expect(normalizedChatViewSource.slice(onTransportErrorIndex))
      .toContain('markTaskProgressStoppedForCurrentStream(\'SSE 连接已断开，正在同步状态\', taskStreamToken)')
  })

  it('会话列表刷新不应无条件恢复任务进度覆盖当前 stream 终态', () => {
    const loadConversations = functionBlock('loadConversations')

    expect(loadConversations).not.toContain('restoreActiveTaskProgress(activeConversationId.value)')
    expect(loadConversations).toContain('await restoreActiveTaskProgress(conversations.value[0].id)')
  })
})
