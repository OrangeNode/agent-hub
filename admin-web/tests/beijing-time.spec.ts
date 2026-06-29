import { describe, expect, it } from 'vitest'
import { formatBeijingDateTime, toBeijingIsoString } from '../src/utils/beijing-time'

describe('北京时间工具', () => {
  it('应按北京时间格式化时间文本', () => {
    expect(formatBeijingDateTime('2026-06-11T02:15:30Z')).toBe('2026-06-11 10:15:30')
    expect(formatBeijingDateTime(null)).toBe('-')
    expect(formatBeijingDateTime('bad-date')).toBe('bad-date')
  })

  it('应生成带东八区偏移的 ISO 时间', () => {
    expect(toBeijingIsoString(Date.UTC(2026, 5, 10, 16, 0, 0))).toBe('2026-06-11T00:00:00+08:00')
  })
})
