import { describe, expect, it, vi } from 'vitest'
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
      openChatStream(
        { prompt: '输出 txt', token: 'token' },
        {
          onToolCall: (data) => events.push({ type: 'call', ...data }),
          onToolOutput: (data) => events.push({ type: 'output', ...data }),
          onToolResult: (data) => events.push({ type: 'result', ...data }),
          onDone: () => {
            window.clearTimeout(timeout)
            resolve()
          },
          onTransportError: reject
        }
      )
    })

    expect(events).toEqual([
      { type: 'call', toolCallId: 'call_1', name: 'write_file' },
      { type: 'output', toolCallId: 'call_1', stream: 'stdout', content: '写入中' },
      { type: 'result', toolCallId: 'call_1', name: 'write_file', status: 'completed', content: '文件写入完成' }
    ])
    vi.unstubAllGlobals()
  })

  it('应该分发协议 meta 和扩展错误事件', async () => {
    const events: unknown[] = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: sseBody(
        'event: meta\ndata: {"protocol":"chat.sse.v1","conversationId":100,"messageId":200,"streamId":"chatmsg_200_20260622_000001","resumable":false}\n\n',
        'event: error\ndata: {"code":"LLM_STREAM_FAILED","message":"模型调用失败","messageId":200,"fatal":true,"recoverable":false,"source":"LLM","status":"FAILED"}\n\n',
        'event: done\ndata: {}\n\n'
      )
    }))

    await new Promise<void>((resolve, reject) => {
      const timeout = window.setTimeout(() => reject(new Error('等待协议事件超时')), 1000)
      openChatStream(
        { prompt: '你好', token: 'token' },
        {
          onMeta: (data) => events.push({ type: 'meta', ...data }),
          onError: (data) => events.push({ type: 'error', ...data }),
          onDone: () => {
            window.clearTimeout(timeout)
            resolve()
          },
          onTransportError: reject
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
        code: 'LLM_STREAM_FAILED',
        message: '模型调用失败',
        messageId: 200,
        fatal: true,
        recoverable: false,
        source: 'LLM',
        status: 'FAILED'
      }
    ])
    vi.unstubAllGlobals()
  })
})
