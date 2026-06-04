import { describe, expect, it } from 'vitest'
import { buildReasoningStatusText, formatReasoningDuration } from '../src/views/reasoning-timer.helpers'

describe('思考链计时展示', () => {
  it('应该用分钟和秒格式化持续时间', () => {
    expect(formatReasoningDuration(88_000)).toBe('1m 28s')
  })

  it('应该至少显示 1 秒', () => {
    expect(formatReasoningDuration(240)).toBe('1s')
  })

  it('流式中应该显示已处理时间', () => {
    expect(buildReasoningStatusText(88_000, false)).toBe('已处理 1m 28s')
  })

  it('结束后应该显示思考用时', () => {
    expect(buildReasoningStatusText(88_000, true)).toBe('已思考 1m 28s')
  })
})
