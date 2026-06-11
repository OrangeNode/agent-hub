import { describe, expect, it } from 'vitest'
import {
  buildControlRequest,
  buildOperationTitle,
  buildRiskTagType,
  canSubmitOperation,
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

  it('应该校验高风险操作的原因和确认文本', () => {
    expect(canSubmitOperation('KILL_VM', '', 'fc-1', 'fc-1')).toBe(false)
    expect(canSubmitOperation('KILL_VM', '异常进程残留', 'wrong', 'fc-1')).toBe(false)
    expect(canSubmitOperation('KILL_VM', '异常进程残留', 'fc-1', 'fc-1')).toBe(true)
    expect(canSubmitOperation('REAP_IDLE', '', '', null)).toBe(true)
  })

  it('应该构造控制请求和操作标题', () => {
    expect(buildControlRequest('CLEAR_RUN_DIR', '释放磁盘', '清空运行目录', true)).toEqual({
      reason: '释放磁盘',
      confirmText: '清空运行目录',
      dryRun: true
    })
    expect(buildOperationTitle('REBUILD_WARM_POOL')).toBe('重建预热池')
    expect(buildOperationTitle('KILL_VM', 'fc-1')).toBe('终止 VM：fc-1')
  })
})
