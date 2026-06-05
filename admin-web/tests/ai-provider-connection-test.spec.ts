import { describe, expect, it } from 'vitest'
import apiSource from '../src/api/ai.ts?raw'
import typeSource from '../src/types/ai.ts?raw'
import viewSource from '../src/views/AiProvidersView.vue?raw'

describe('AI 供应商连通性测试', () => {
  it('应该定义连通性测试返回类型', () => {
    expect(typeSource).toContain('export interface AiProviderConnectionTestResult')
    expect(typeSource).toContain('success: boolean')
    expect(typeSource).toContain('statusCode?: number | null')
  })

  it('应该提供供应商连通性测试 API', () => {
    expect(apiSource).toContain('testAiProviderConnection')
    expect(apiSource).toContain('/ai/providers/${providerId}/test-connection')
  })

  it('应该在供应商列表操作列展示测试按钮并处理结果提示', () => {
    expect(viewSource).toContain('testAiProviderConnection')
    expect(viewSource).toContain('testingProviderIds')
    expect(viewSource).toContain("default: () => '测试'")
  })
})
