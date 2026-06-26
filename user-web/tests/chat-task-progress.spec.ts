import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchActiveTaskProgress, type ChatTaskProgressRecord } from '../src/api/chat'
import { http } from '../src/api/http'
import {
  buildTaskProgressText,
  normalizeTaskProgress,
  taskStepStatusText
} from '../src/views/chat-task-progress.helpers'

vi.mock('../src/api/http', () => ({
  http: {
    get: vi.fn()
  }
}))

function progressOf(overrides: Partial<ChatTaskProgressRecord>): ChatTaskProgressRecord {
  return {
    taskRunId: 123,
    mode: 'AUTO',
    status: 'RUNNING',
    title: '生成报告',
    currentStepIndex: 2,
    totalSteps: 5,
    revision: 1,
    steps: [],
    ...overrides
  }
}

describe('聊天任务进度辅助函数', () => {
  beforeEach(() => {
    vi.mocked(http.get).mockReset()
  })

  it('应该生成折叠状态摘要文案', () => {
    expect(buildTaskProgressText(undefined)).toBe('')
    expect(buildTaskProgressText(null)).toBe('')
    expect(buildTaskProgressText(progressOf({ status: 'PLANNING' }))).toBe('任务进度：准备中')
    expect(buildTaskProgressText(progressOf({ status: 'RUNNING', currentStepIndex: 2, totalSteps: 5 }))).toBe(
      '任务进度：正在执行 2 / 5'
    )
    expect(buildTaskProgressText(progressOf({ status: 'COMPLETED', currentStepIndex: 2, totalSteps: 5 }))).toBe(
      '任务进度：已完成 5 / 5'
    )
    expect(buildTaskProgressText(progressOf({ status: 'FAILED', currentStepIndex: 3, totalSteps: 5 }))).toBe(
      '任务进度：执行失败 3 / 5'
    )
    expect(buildTaskProgressText(progressOf({ status: 'STOPPED', currentStepIndex: 3, totalSteps: 5 }))).toBe(
      '任务进度：已停止 3 / 5'
    )
  })

  it('应该规范化默认值并按步骤序号排序', () => {
    const normalized = normalizeTaskProgress({
      taskRunId: 123,
      mode: '',
      status: '',
      title: '',
      planSummary: null,
      currentStepIndex: '2',
      totalSteps: '3',
      revision: '4',
      errorMessage: null,
      steps: [
        { stepIndex: '2', name: '第二步', status: 'RUNNING' },
        { stepIndex: '1', name: '', status: '' }
      ]
    } as unknown as ChatTaskProgressRecord)

    expect(normalized).toMatchObject({
      taskRunId: 123,
      mode: '',
      status: 'PLANNING',
      title: '任务进度',
      planSummary: '',
      currentStepIndex: 2,
      totalSteps: 3,
      revision: 4,
      errorMessage: ''
    })
    expect(normalized.steps).toEqual([
      {
        stepIndex: 1,
        name: '步骤 1',
        status: 'PENDING',
        statusText: '等待中',
        errorMessage: ''
      },
      {
        stepIndex: 2,
        name: '第二步',
        status: 'RUNNING',
        statusText: '进行中',
        errorMessage: ''
      }
    ])
  })

  it('应该把异常数值归一化为非负进度', () => {
    const negative = progressOf({
      status: 'RUNNING',
      currentStepIndex: '-1' as unknown as number,
      totalSteps: '-3' as unknown as number
    })

    expect(normalizeTaskProgress(negative)).toMatchObject({
      currentStepIndex: 0,
      totalSteps: 0
    })
    expect(buildTaskProgressText(negative)).toBe('任务进度：正在执行 0 / 0')

    const missing = normalizeTaskProgress({
      taskRunId: 123,
      mode: 'AUTO',
      status: 'RUNNING',
      title: '生成报告',
      revision: 1,
      steps: []
    } as unknown as ChatTaskProgressRecord)

    expect(missing.currentStepIndex).toBe(0)
    expect(missing.totalSteps).toBe(0)
  })

  it('应该返回步骤状态中文文案', () => {
    expect(taskStepStatusText('RUNNING')).toBe('进行中')
    expect(taskStepStatusText('COMPLETED')).toBe('已完成')
    expect(taskStepStatusText('FAILED')).toBe('失败')
    expect(taskStepStatusText('STOPPED')).toBe('已停止')
    expect(taskStepStatusText('SKIPPED')).toBe('已跳过')
    expect(taskStepStatusText('PENDING')).toBe('等待中')
    expect(taskStepStatusText('UNKNOWN')).toBe('等待中')
  })

  it('应该请求当前会话的活动任务进度', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: {
        code: 200,
        message: '成功',
        data: null
      }
    })

    const result = await fetchActiveTaskProgress(42)

    expect(http.get).toHaveBeenCalledWith('/chat/conversations/42/tasks/active')
    expect(result).toBeNull()
  })
})
