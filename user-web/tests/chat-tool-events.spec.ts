import { describe, expect, it } from 'vitest'
import {
  appendReasoningEvent,
  applyToolCallEvent,
  applyToolOutputEvent,
  applyToolResultEvent,
  createInitialProcessEvents,
  createProcessEvents
} from '../src/views/chat-tool-events.helpers'

describe('聊天工具调用链状态', () => {
  it('应该把思考和工具调用按顺序收进处理过程', () => {
    const events = createProcessEvents()

    appendReasoningEvent(events, '先写入代码。')
    applyToolCallEvent(events, { toolCallId: 'call_1', name: 'write_file' })
    applyToolOutputEvent(events, { toolCallId: 'call_1', stream: 'stdout', content: '写入中' })
    applyToolResultEvent(events, {
      toolCallId: 'call_1',
      name: 'write_file',
      status: 'completed',
      content: '文件写入完成'
    })

    expect(events).toEqual([
      {
        type: 'reasoning',
        content: '先写入代码。'
      },
      {
        type: 'tool',
        tool: {
          toolCallId: 'call_1',
          name: 'write_file',
          status: 'completed',
          stdout: '写入中',
          stderr: '',
          result: '文件写入完成'
        }
      }
    ])
  })

  it('相邻思考片段应该合并，保持处理过程简洁', () => {
    const events = createProcessEvents()

    appendReasoningEvent(events, '第一段')
    appendReasoningEvent(events, '，继续思考')

    expect(events).toEqual([
      {
        type: 'reasoning',
        content: '第一段，继续思考'
      }
    ])
  })

  it('未知 toolCallId 的输出也应该保留，避免流事件乱序时丢失信息', () => {
    const events = createProcessEvents()

    applyToolOutputEvent(events, { toolCallId: 'call_late', stream: 'stderr', content: 'warning' })

    expect(events).toEqual([
      {
        type: 'tool',
        tool: {
          toolCallId: 'call_late',
          name: '工具调用',
          status: 'running',
          stdout: '',
          stderr: 'warning',
          result: ''
        }
      }
    ])
  })

  it('历史消息已有过程事件时应该优先恢复工具链', () => {
    const events = createInitialProcessEvents('旧 reasoning', [
      {
        type: 'tool',
        tool: {
          toolCallId: 'call_1',
          name: 'exec',
          status: 'completed',
          stdout: 'ok',
          stderr: '',
          result: 'stdout:\nok\nstderr:\n'
        }
      }
    ])

    expect(events).toEqual([
      {
        type: 'tool',
        tool: {
          toolCallId: 'call_1',
          name: 'exec',
          status: 'completed',
          stdout: 'ok',
          stderr: '',
          result: 'stdout:\nok\nstderr:\n'
        }
      }
    ])
  })

  it('历史消息没有过程事件时应该用 reasoningContent 兜底', () => {
    expect(createInitialProcessEvents('正在分析', null)).toEqual([
      {
        type: 'reasoning',
        content: '正在分析'
      }
    ])
  })
})
