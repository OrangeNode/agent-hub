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

  it('供应商记录应该包含扩展请求配置用于编辑回填', () => {
    expect(typeSource).toContain('extraHeaders: string')
    expect(typeSource).toContain('extraBody: string')
  })

  it('协议选择应该只保留 OpenAI 兼容协议', () => {
    expect(viewSource).toContain("{ label: 'OpenAI 兼容协议', value: 'OPENAI_COMPATIBLE' }")
    expect(viewSource).not.toContain('OPENAI_CHAT_COMPLETIONS')
    expect(viewSource).not.toContain('OPENAI_RESPONSES')
    expect(viewSource).not.toContain('ANTHROPIC_MESSAGES')
  })

  it('编辑和状态切换供应商时应该保留扩展请求配置', () => {
    expect(viewSource).toContain('formValue.extraHeaders = provider.extraHeaders ??')
    expect(viewSource).toContain('formValue.extraBody = provider.extraBody ??')
    expect(viewSource).toContain('extraHeaders: provider.extraHeaders ??')
    expect(viewSource).toContain('extraBody: provider.extraBody ??')
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
