import { describe, expect, it } from 'vitest'
import type { ApiResponse } from '../src/api/http'
import type { LoginResult } from '../src/api/auth'
import { resolveLoginResult } from '../src/api/auth'

describe('用户端登录响应处理', () => {
  it('登录失败且响应数据为空时，应该抛出后端错误消息', () => {
    const response: ApiResponse<LoginResult | null> = {
      code: 401,
      message: '用户名或密码错误',
      data: null
    }

    expect(() => resolveLoginResult(response)).toThrow('用户名或密码错误')
  })
})
