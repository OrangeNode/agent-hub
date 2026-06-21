import { describe, expect, it } from 'vitest'
import { assistantDisplayContent, findStoppableAssistantMessage, resolveSendButtonDisabled } from '../src/views/ChatView.vue'
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

  it('中止时应兜底标记当前最后一条助手消息', () => {
    const messages = [
      { role: 'user', status: 'COMPLETED', content: '问题' },
      { role: 'assistant', status: undefined, content: '' }
    ]

    expect(findStoppableAssistantMessage(messages)).toBe(messages[1])
  })
  it('空内容的失败助手消息不应继续显示正在生成', () => {
    expect(assistantDisplayContent({
      role: 'assistant',
      status: 'FAILED',
      content: ''
    })).toBe('生成失败')
  })

  it('流式错误应立即把当前助手占位消息标记为失败', () => {
    const onErrorIndex = normalizedChatViewSource.indexOf('onError: (data) => {')
    const onTransportErrorIndex = normalizedChatViewSource.indexOf('onTransportError: () => {')
    const onDoneIndex = normalizedChatViewSource.indexOf('onDone: () => {')

    expect(normalizedChatViewSource.slice(onErrorIndex, onDoneIndex))
      .toContain('markAssistantFailed(findAssistantMessage(currentAssistantMessageId))')
    expect(normalizedChatViewSource.slice(onTransportErrorIndex))
      .toContain('markAssistantFailed(findAssistantMessage(currentAssistantMessageId))')
  })
})
