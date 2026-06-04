import { describe, expect, it } from 'vitest'
import typeSource from '../src/types/ai.ts?raw'
import viewSource from '../src/views/AiModelsView.vue?raw'

describe('AI 模型图片能力表单', () => {
  it('应该在默认表单中关闭图片输入能力', () => {
    expect(viewSource).toContain('imageInputSupported: false')
    expect(viewSource).toContain('imageTokenEstimate: null')
  })

  it('应该允许保存图片 token 估算并在关闭图片输入时清空估算值', () => {
    expect(typeSource).toContain('imageInputSupported: boolean')
    expect(typeSource).toContain('imageTokenEstimate?: number | null')
    expect(viewSource).toContain('imageInputSupported: formValue.imageInputSupported')
    expect(viewSource).toContain(
      'imageTokenEstimate: formValue.imageInputSupported ? formValue.imageTokenEstimate : null'
    )
  })

  it('应该展示图片输入开关、估算输入和表格图片能力标签', () => {
    expect(viewSource).toContain('支持图片输入')
    expect(viewSource).toContain('图片 token 估算')
    expect(viewSource).toContain('placeholder="默认 1500"')
    expect(viewSource).toContain(':min="1"')
    expect(viewSource).toContain("default: () => '图片'")
  })
})
