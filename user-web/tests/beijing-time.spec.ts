import { describe, expect, it } from 'vitest'
import { toBeijingIsoString } from '../src/utils/beijing-time'

describe('北京时间工具', () => {
  it('应生成带东八区偏移的 ISO 时间', () => {
    expect(toBeijingIsoString(Date.UTC(2026, 5, 10, 16, 0, 0))).toBe('2026-06-11T00:00:00+08:00')
  })
})
