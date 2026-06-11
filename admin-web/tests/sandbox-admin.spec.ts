import { describe, expect, it } from 'vitest'
import {
  buildRiskTagType,
  expectedConfirmText,
  formatBytes,
  formatDurationSeconds,
  requiresStrongConfirm
} from '../src/views/sandbox-admin.helpers'

describe('Sandbox 运维辅助函数', () => {
  it('应该把风险级别映射成 Naive UI 标签类型', () => {
    expect(buildRiskTagType('CRITICAL')).toBe('error')
    expect(buildRiskTagType('WARN')).toBe('warning')
    expect(buildRiskTagType('INFO')).toBe('info')
  })

  it('应该格式化资源大小和空闲时长', () => {
    expect(formatBytes(1_073_741_824)).toBe('1.00 GB')
    expect(formatBytes(1024)).toBe('1.00 KB')
    expect(formatDurationSeconds(65)).toBe('1分5秒')
  })

  it('应该识别强确认操作', () => {
    expect(requiresStrongConfirm('CLEAR_RUN_DIR')).toBe(true)
    expect(requiresStrongConfirm('REAP_IDLE')).toBe(false)
    expect(expectedConfirmText('CLEAR_RUN_DIR', 'ignored')).toBe('清空运行目录')
    expect(expectedConfirmText('KILL_VM', 'fc-1')).toBe('fc-1')
  })
})
