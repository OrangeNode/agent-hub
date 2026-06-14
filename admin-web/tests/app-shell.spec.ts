import { describe, expect, it } from 'vitest'
import appSource from '../src/App.vue?raw'
import layoutSource from '../src/layouts/AdminLayout.vue?raw'
import aiProvidersSource from '../src/views/AiProvidersView.vue?raw'

describe('管理端应用外壳', () => {
  it('应该为 Naive UI 配置中文 locale', () => {
    expect(appSource).toContain(':locale="zhCN"')
    expect(appSource).toContain(':date-locale="dateZhCN"')
    expect(appSource).toContain('import { dateZhCN, NConfigProvider, NMessageProvider, zhCN')
  })

  it('内容区应该限制子页面宽度并提供横向滚动', () => {
    expect(layoutSource).toMatch(/\.content-body\s*\{[\s\S]*min-width:\s*0;/)
    expect(layoutSource).toMatch(/\.content-body\s*\{[\s\S]*overflow:\s*auto;/)
    expect(layoutSource).toMatch(/:deep\(\.page\)\s*\{[\s\S]*max-width:\s*100%;/)
  })

  it('供应商宽表应该启用横向滚动而不是撑开页面', () => {
    expect(aiProvidersSource).toContain(':scroll-x="1120"')
    expect(aiProvidersSource).toContain("fixed: 'right'")
  })
})
