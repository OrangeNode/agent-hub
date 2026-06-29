import { describe, expect, it } from 'vitest'
import {
  applyMessageCompletedIfStreaming,
  applyStreamConversationId,
  applyStreamAssistantMessageId,
  assistantDisplayContent,
  findStoppableAssistantMessage,
  resolveConversationSwitchStopAction,
  resolveSendButtonDisabled
} from '../src/views/ChatView.vue'
import chatApiSource from '../src/api/chat.ts?raw'
import chatViewSource from '../src/views/ChatView.vue?raw'

const normalizedChatApiSource = chatApiSource.replace(/\r\n/g, '\n')
const normalizedChatViewSource = chatViewSource.replace(/\r\n/g, '\n')

function functionBlock(name: string) {
  const start = normalizedChatViewSource.indexOf(`function ${name}`)
  if (start < 0) {
    return ''
  }

  const nextFunction = normalizedChatViewSource.indexOf('\nfunction ', start + 1)
  return normalizedChatViewSource.slice(start, nextFunction < 0 ? undefined : nextFunction)
}

function computedBlock(name: string) {
  const start = normalizedChatViewSource.indexOf(`const ${name} = computed(() =>`)
  if (start < 0) {
    return ''
  }

  const nextConst = normalizedChatViewSource.indexOf('\nconst ', start + 1)
  return normalizedChatViewSource.slice(start, nextConst < 0 ? undefined : nextConst)
}

describe('聊天生成中止', () => {
  it('生成中发送按钮应切换为可点击的中止按钮', () => {
    expect(normalizedChatViewSource).toContain(':disabled="sendButtonDisabled"')
    expect(normalizedChatViewSource).toContain(':aria-label="streaming ? \'中止生成\' : \'发送\'"')
    expect(normalizedChatViewSource).toContain(':title="streaming ? \'中止生成\' : \'发送\'"')
    expect(normalizedChatViewSource).toContain('v-if="streaming"')
    expect(normalizedChatViewSource).toContain('<rect x="5" y="5" width="14" height="14" rx="2.5" />')
  })

  it('生成中应保持蓝色按钮和白色停止方块', () => {
    expect(normalizedChatViewSource).toContain(`.send-orb.is-stopping {
  background: #2563eb;
}`)
    expect(normalizedChatViewSource).toContain(`.send-orb.is-stopping rect {
  fill: currentColor;
  stroke: none;
}`)
  })

  it('生成中点击按钮应调用中止逻辑', () => {
    const handleSend = functionBlock('handleSend')

    expect(handleSend).toContain(`if (streaming.value) {
    stopCurrentStream()
    return
  }`)
  })

  it('停止生成接口应同步服务端消息状态', () => {
    expect(normalizedChatApiSource).toContain('export async function stopGeneration(messageId: number)')
    expect(normalizedChatApiSource).toContain('await http.post(`/chat/messages/${messageId}/stop`)')
  })

  it('停止当前正数消息时应调用停止生成接口', () => {
    const stopCurrentStream = functionBlock('stopCurrentStream')

    expect(normalizedChatViewSource).toContain('stopGeneration')
    expect(stopCurrentStream).toContain('const assistantMessage = findStoppableAssistantMessage(currentMessages.value)')
    expect(stopCurrentStream).toContain('assistantMessage && assistantMessage.id > 0')
    expect(stopCurrentStream).toContain('requestStopGeneration(assistantMessage.id)')
    expect(stopCurrentStream).not.toContain('currentAssistantMessageId')
  })

  it('停止真实消息时应等待停止请求完成后再刷新历史消息', () => {
    const stopCurrentStream = functionBlock('stopCurrentStream')
    const refreshAfterStopping = functionBlock('refreshAfterStopping')
    const requestStopIndex = stopCurrentStream.indexOf('requestStopGeneration(assistantMessage.id)')
    const closeStreamIndex = stopCurrentStream.indexOf('closeStream()', requestStopIndex)
    const refreshIndex = stopCurrentStream.indexOf('refreshAfterStopping()', requestStopIndex)

    expect(refreshAfterStopping).toContain('void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)')
    expect(refreshAfterStopping).toContain('void refreshActiveArtifacts()')
    expect(refreshAfterStopping).toContain('void loadConversations()')
    expect(stopCurrentStream).toContain('void requestStopGeneration(assistantMessage.id).finally(() => {')
    expect(requestStopIndex).toBeGreaterThanOrEqual(0)
    expect(closeStreamIndex).toBeGreaterThan(requestStopIndex)
    expect(refreshIndex).toBeGreaterThan(closeStreamIndex)
  })

  it('停止真实消息时应先请求后中断 SSE，临时消息仍本地中断', () => {
    const stopCurrentStream = functionBlock('stopCurrentStream')
    const positiveBranchIndex = stopCurrentStream.indexOf('if (assistantMessage && assistantMessage.id > 0) {')
    const requestStopIndex = stopCurrentStream.indexOf('requestStopGeneration(assistantMessage.id)', positiveBranchIndex)
    const closeAfterRequestIndex = stopCurrentStream.indexOf('closeStream()', requestStopIndex)
    const localStopIndex = stopCurrentStream.indexOf('markCurrentAssistantStopped()', closeAfterRequestIndex)
    const localCloseIndex = stopCurrentStream.indexOf('closeStream()', localStopIndex)

    expect(positiveBranchIndex).toBeGreaterThanOrEqual(0)
    expect(requestStopIndex).toBeGreaterThan(positiveBranchIndex)
    expect(closeAfterRequestIndex).toBeGreaterThan(requestStopIndex)
    expect(localStopIndex).toBeGreaterThan(closeAfterRequestIndex)
    expect(localCloseIndex).toBeGreaterThan(localStopIndex)
  })

  it('切换会话时应在离开当前会话前终止当前流', () => {
    const switchConversation = functionBlock('switchConversation')
    const stopBeforeSwitch = functionBlock('stopCurrentStreamBeforeLeavingConversation')
    const stopIndex = switchConversation.indexOf('stopCurrentStreamBeforeLeavingConversation()')
    const switchIndex = switchConversation.indexOf('activeConversationId.value = conversationId')

    expect(stopIndex).toBeGreaterThanOrEqual(0)
    expect(switchIndex).toBeGreaterThan(stopIndex)
    expect(stopBeforeSwitch).toContain('resolveConversationSwitchStopAction({')
    expect(stopBeforeSwitch).toContain('markCurrentAssistantStopped')
    expect(stopBeforeSwitch).toContain('closeStream()')
    expect(stopBeforeSwitch).toContain('requestStopGeneration(stopAction.messageId)')
    expect(stopBeforeSwitch).not.toContain('refreshAfterStopping()')
  })

  it('切换会话终止当前流时只对真实消息请求后端停止', () => {
    expect(resolveConversationSwitchStopAction({
      streaming: true,
      assistantMessageId: 200
    })).toEqual({
      shouldStop: true,
      messageId: 200
    })
    expect(resolveConversationSwitchStopAction({
      streaming: true,
      assistantMessageId: -1
    })).toEqual({
      shouldStop: true,
      messageId: null
    })
    expect(resolveConversationSwitchStopAction({
      streaming: false,
      assistantMessageId: 200
    })).toEqual({
      shouldStop: false,
      messageId: null
    })
  })

  it('meta 事件应提前把临时助手消息替换为真实消息 ID', () => {
    const messages = [
      { id: -2, role: 'user' },
      { id: -1, role: 'assistant' }
    ]

    const currentMessageId = applyStreamAssistantMessageId(messages, -1, 200)

    expect(currentMessageId).toBe(200)
    expect(messages[1].id).toBe(200)
  })

  it('meta 事件应提前把临时会话替换为真实会话 ID', () => {
    const messageMap: Record<number, Array<{ id: number; role: string }>> = {
      [-100]: [
        { id: -2, role: 'user' },
        { id: 200, role: 'assistant' }
      ]
    }

    const currentConversationId = applyStreamConversationId(messageMap, -100, 100)

    expect(currentConversationId).toBe(100)
    expect(messageMap[100]).toEqual([
      { id: -2, role: 'user' },
      { id: 200, role: 'assistant' }
    ])
    expect(messageMap[-100]).toBeUndefined()
  })

  it('流式 meta 和 message 事件应复用同一消息 ID 同步逻辑', () => {
    expect(normalizedChatViewSource).toContain('const applyStreamAssistantMessageIdFromEvent = (messageId?: number | null) => {')
    expect(normalizedChatViewSource).toContain('const applyStreamConversationIdFromEvent = (streamConversationId?: number | null) => {')
    expect(normalizedChatViewSource).toContain('onMeta: (data) => {')
    expect(normalizedChatViewSource).toContain('applyStreamConversationIdFromEvent(data.conversationId)')
    expect(normalizedChatViewSource).toContain('applyStreamAssistantMessageIdFromEvent(data.messageId)')
    expect(normalizedChatViewSource).toContain('onMessage: (data) => {')
  })

  it('按钮禁用状态不应包含流式生成状态', () => {
    const sendButtonDisabled = computedBlock('sendButtonDisabled')

    expect(sendButtonDisabled).toBeTruthy()
    expect(sendButtonDisabled).toContain('resolveSendButtonDisabled({')
    expect(sendButtonDisabled).toContain('streaming: streaming.value')
  })

  it('生成中即使输入为空也应允许点击中止', () => {
    expect(resolveSendButtonDisabled({
      streaming: true,
      hasUploadingImage: false,
      hasFailedImage: false,
      canSubmitContent: false,
      imageInputBlocked: false
    })).toBe(false)
  })

  it('非生成中输入为空仍应禁用发送', () => {
    expect(resolveSendButtonDisabled({
      streaming: false,
      hasUploadingImage: false,
      hasFailedImage: false,
      canSubmitContent: false,
      imageInputBlocked: false
    })).toBe(true)
  })

  it('中止时应只标记正在流式生成的助手消息', () => {
    const messages = [
      { role: 'user', status: 'COMPLETED', content: '问题' },
      { role: 'assistant', status: 'COMPLETED', content: '完整回复' },
      { role: 'assistant', status: 'STREAMING', content: '' }
    ]

    expect(findStoppableAssistantMessage(messages)).toBe(messages[2])
    expect(findStoppableAssistantMessage([
      { role: 'assistant', status: 'COMPLETED', content: '完整回复' },
      { role: 'assistant', status: 'STOPPED', content: '部分回复' }
    ])).toBeUndefined()
  })

  it('本地停止后后续完成事件不应把消息改回已完成', () => {
    const stoppedMessage = { status: 'STOPPED' }
    const streamingMessage = { status: 'STREAMING' }

    expect(applyMessageCompletedIfStreaming(stoppedMessage, 'COMPLETED')).toBe(false)
    expect(stoppedMessage.status).toBe('STOPPED')
    expect(applyMessageCompletedIfStreaming(streamingMessage, 'COMPLETED')).toBe(true)
    expect(streamingMessage.status).toBe('COMPLETED')
  })

  it('空内容的失败助手消息不应继续显示正在生成', () => {
    expect(assistantDisplayContent({
      status: 'FAILED',
      content: ''
    })).toBe('生成失败')
  })

  it('失败助手消息无正文时应优先显示流内错误消息', () => {
    expect(assistantDisplayContent({ status: 'FAILED', content: '', errorMessage: '模型调用失败' })).toBe('模型调用失败')
  })

  it('传输断线本地停止后应显示同步状态提示', () => {
    expect(assistantDisplayContent({
      status: 'STOPPED',
      content: '',
      errorMessage: 'SSE 连接已断开，正在同步状态'
    })).toBe('SSE 连接已断开，正在同步状态')
  })

  it('流式错误应记录失败消息，传输错误只刷新不永久失败', () => {
    const onErrorIndex = normalizedChatViewSource.indexOf('onError: (data) => {')
    const onTransportErrorIndex = normalizedChatViewSource.indexOf('onTransportError: () => {')
    const onDoneIndex = normalizedChatViewSource.indexOf('onDone: () => {')

    expect(normalizedChatViewSource.slice(onErrorIndex, onDoneIndex))
      .toContain('if (shouldApplyStreamingEvent(assistantMessage))')
    expect(normalizedChatViewSource.slice(onErrorIndex, onDoneIndex))
      .toContain('markAssistantFailed(assistantMessage, data.message ?? \'流式响应失败\')')
    expect(normalizedChatViewSource.slice(onTransportErrorIndex))
      .not.toContain('markAssistantFailed(findAssistantMessage(currentAssistantMessageId))')
    expect(normalizedChatViewSource.slice(onTransportErrorIndex))
      .toContain('markCurrentAssistantStopped(\'SSE 连接已断开，正在同步状态\')')
    expect(normalizedChatViewSource.slice(onTransportErrorIndex))
      .toContain('void refreshActiveConversationMessages().finally(revokeSentPreviewUrls)')
  })
})
