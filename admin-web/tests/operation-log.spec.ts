import { describe, expect, it } from 'vitest'
import {
  buildOperationStatusTagType,
  formatJsonPreview,
  normalizeLogLimit,
  summarizeOperationTarget
} from '../src/views/operation-log.helpers'

describe('操作日志辅助函数', () => {
  it('应该映射操作状态标签', () => {
    expect(buildOperationStatusTagType('SUCCESS')).toBe('success')
    expect(buildOperationStatusTagType('FAILED')).toBe('error')
    expect(buildOperationStatusTagType('UNKNOWN')).toBe('default')
  })

  it('应该归一化日志查询条数', () => {
    expect(normalizeLogLimit(0)).toBe(100)
    expect(normalizeLogLimit(80)).toBe(80)
    expect(normalizeLogLimit(999)).toBe(500)
  })

  it('应该格式化 JSON 预览和目标摘要', () => {
    expect(formatJsonPreview('{"a":1}')).toContain('\n')
    expect(formatJsonPreview('plain')).toBe('plain')
    expect(summarizeOperationTarget('VM', 'fc-1')).toBe('VM / fc-1')
    expect(summarizeOperationTarget(null, null)).toBe('--')
  })
})
