import { describe, expect, it } from 'vitest'
import { resolveTabTitle } from '../src/stores/tabs.helpers'

describe('标签页标题解析', () => {
  it('应该优先使用路由标题', () => {
    const title = resolveTabTitle({
      name: 'system-users',
      path: '/system/users',
      meta: { title: '用户管理' }
    })

    expect(title).toBe('用户管理')
  })

  it('缺少标题时应该回退到路由名称', () => {
    const title = resolveTabTitle({
      name: 'custom-page',
      path: '/custom-page',
      meta: {}
    })

    expect(title).toBe('custom-page')
  })
})
