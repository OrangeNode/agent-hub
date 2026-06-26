import { afterEach, describe, expect, it, vi } from 'vitest'
import { openChatStream } from '../src/api/chat'

function sseBody(...frames: string[]) {
  const encoder = new TextEncoder()
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) {
        controller.enqueue(encoder.encode(frame))
      }
      controller.close()
    }
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('聊天流工具事件', () => {
  it('应该按 toolCallId 分发工具调用链事件', async () => {
    const events: unknown[] = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: sseBody(
        'event: tool_call\ndata: {"toolCallId":"call_1","name":"write_file"}\n\n',
        'event: tool_output\ndata: {"toolCallId":"call_1","stream":"stdout","content":"写入中"}\n\n',
        'event: tool_result\ndata: {"toolCallId":"call_1","name":"write_file","status":"completed","content":"文件写入完成"}\n\n',
        'event: done\ndata: {}\n\n'
      )
    }))

    await new Promise<void>((resolve, reject) => {
      const timeout = window.setTimeout(() => reject(new Error('等待工具事件超时')), 1000)
      const finish = (callback: () => void) => {
        window.clearTimeout(timeout)
        callback()
      }
      openChatStream(
        { prompt: '输出 txt', token: 'token' },
        {
          onToolCall: (data) => events.push({ type: 'call', ...data }),
          onToolOutput: (data) => events.push({ type: 'output', ...data }),
          onToolResult: (data) => events.push({ type: 'result', ...data }),
          onDone: () => finish(resolve),
          onTransportError: (error) => finish(() => reject(error))
        }
      )
    })

    expect(events).toEqual([
      { type: 'call', toolCallId: 'call_1', name: 'write_file' },
      { type: 'output', toolCallId: 'call_1', stream: 'stdout', content: '写入中' },
      { type: 'result', toolCallId: 'call_1', name: 'write_file', status: 'completed', content: '文件写入完成' }
    ])
  })

  it('应该分发协议 meta 和扩展错误事件', async () => {
    const events: unknown[] = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: sseBody(
        'event: meta\ndata: {"protocol":"chat.sse.v1","conversationId":100,"messageId":200,"streamId":"chatmsg_200_20260622_000001","resumable":false}\n\n',
        'event: error\ndata: {"code":"TOOL_LOOP_FAILED","message":"工具调用失败","messageId":200,"fatal":true,"recoverable":false,"retryable":false,"source":"TOOL","status":"FAILED","traceId":"trace-1","details":{"toolCallId":"call_1"}}\n\n',
        'event: done\ndata: {}\n\n'
      )
    }))

    await new Promise<void>((resolve, reject) => {
      const timeout = window.setTimeout(() => reject(new Error('等待协议事件超时')), 1000)
      const finish = (callback: () => void) => {
        window.clearTimeout(timeout)
        callback()
      }
      openChatStream(
        { prompt: '你好', token: 'token' },
        {
          onMeta: (data) => events.push({ type: 'meta', ...data }),
          onError: (data) => events.push({ type: 'error', ...data }),
          onDone: () => finish(resolve),
          onTransportError: (error) => finish(() => reject(error))
        }
      )
    })

    expect(events).toEqual([
      {
        type: 'meta',
        protocol: 'chat.sse.v1',
        conversationId: 100,
        messageId: 200,
        streamId: 'chatmsg_200_20260622_000001',
        resumable: false
      },
      {
        type: 'error',
        code: 'TOOL_LOOP_FAILED',
        message: '工具调用失败',
        messageId: 200,
        fatal: true,
        recoverable: false,
        retryable: false,
        source: 'TOOL',
        status: 'FAILED',
        traceId: 'trace-1',
        details: {
          toolCallId: 'call_1'
        }
      }
    ])
  })

  it('响应体未发送 done 直接结束时应触发传输错误', async () => {
    const events: unknown[] = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: sseBody(
        'event: meta\ndata: {"protocol":"chat.sse.v1","conversationId":100,"messageId":200,"streamId":"chatmsg_200_20260622_000001","resumable":false}\n\n'
      )
    }))

    await new Promise<void>((resolve, reject) => {
      const timeout = window.setTimeout(() => reject(new Error('等待传输错误超时')), 1000)
      const finish = (callback: () => void) => {
        window.clearTimeout(timeout)
        callback()
      }
      openChatStream(
        { prompt: '你好', token: 'token' },
        {
          onMeta: (data) => events.push({ type: 'meta', ...data }),
          onDone: () => finish(() => reject(new Error('不应在缺少 done 时正常结束'))),
          onTransportError: (error) => finish(() => {
            expect(error).toBeInstanceOf(Error)
            expect((error as Error).message).toBe('SSE 连接在完成事件前已断开')
            resolve()
          })
        }
      )
    })

    expect(events).toEqual([
      {
        type: 'meta',
        protocol: 'chat.sse.v1',
        conversationId: 100,
        messageId: 200,
        streamId: 'chatmsg_200_20260622_000001',
        resumable: false
      }
    ])
  })

  it('应该分发任务进度开始和完成事件', async () => {
    const events: unknown[] = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: sseBody(
        'event: task_progress_started\ndata: {"taskRunId":"task-1","mode":"AUTO","status":"PLANNING","title":"生成报告","planSummary":"先检索后写作","currentStepIndex":0,"totalSteps":2,"revision":1,"steps":[{"stepIndex":1,"name":"检索资料","status":"PENDING"},{"stepIndex":2,"name":"撰写报告","status":"PENDING"}]}\n\n',
        'event: task_progress_completed\ndata: {"taskRunId":"task-1","mode":"AUTO","status":"COMPLETED","title":"生成报告","planSummary":"先检索后写作","currentStepIndex":2,"totalSteps":2,"revision":3,"steps":[{"stepIndex":1,"name":"检索资料","status":"COMPLETED"},{"stepIndex":2,"name":"撰写报告","status":"COMPLETED"}]}\n\n',
        'event: done\ndata: {}\n\n'
      )
    }))

    await new Promise<void>((resolve, reject) => {
      const timeout = window.setTimeout(() => reject(new Error('等待任务进度事件超时')), 1000)
      const finish = (callback: () => void) => {
        window.clearTimeout(timeout)
        callback()
      }
      openChatStream(
        { prompt: '生成报告', token: 'token' },
        {
          onTaskProgressStarted: (data) => events.push({ type: 'started', ...data }),
          onTaskProgressCompleted: (data) => events.push({ type: 'completed', ...data }),
          onDone: () => finish(resolve),
          onTransportError: (error) => finish(() => reject(error))
        }
      )
    })

    expect(events).toEqual([
      {
        type: 'started',
        taskRunId: 'task-1',
        mode: 'AUTO',
        status: 'PLANNING',
        title: '生成报告',
        planSummary: '先检索后写作',
        currentStepIndex: 0,
        totalSteps: 2,
        revision: 1,
        steps: [
          { stepIndex: 1, name: '检索资料', status: 'PENDING' },
          { stepIndex: 2, name: '撰写报告', status: 'PENDING' }
        ]
      },
      {
        type: 'completed',
        taskRunId: 'task-1',
        mode: 'AUTO',
        status: 'COMPLETED',
        title: '生成报告',
        planSummary: '先检索后写作',
        currentStepIndex: 2,
        totalSteps: 2,
        revision: 3,
        steps: [
          { stepIndex: 1, name: '检索资料', status: 'COMPLETED' },
          { stepIndex: 2, name: '撰写报告', status: 'COMPLETED' }
        ]
      }
    ])
  })
})
