import { describe, expect, it } from 'vitest'
import { buildUserSearchParams } from '../src/views/system-user.helpers'

describe('用户搜索参数构建', () => {
  it('应该只提交有值的多条件搜索参数', () => {
    const params = buildUserSearchParams({
      username: 'zhang',
      displayName: '张三',
      enabled: 'true',
      roleCode: 'USER'
    })

    expect(params).toEqual({
      username: 'zhang',
      displayName: '张三',
      enabled: 'true',
      roleCode: 'USER'
    })
  })

  it('空条件时应该返回空对象', () => {
    expect(buildUserSearchParams({
      username: '',
      displayName: '',
      enabled: null,
      roleCode: null
    })).toEqual({})
  })
})
