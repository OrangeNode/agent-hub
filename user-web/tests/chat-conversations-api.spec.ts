import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchConversations } from '../src/api/chat'
import { http } from '../src/api/http'

vi.mock('../src/api/http', () => ({
  http: {
    get: vi.fn()
  }
}))

describe('聊天会话列表接口', () => {
  beforeEach(() => {
    vi.mocked(http.get).mockReset()
  })

  it('应该按 limit 和 offset 分页请求会话列表', async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: {
        code: 200,
        message: '成功',
        data: {
          items: [],
          total: 42,
          limit: 20,
          offset: 40
        }
      }
    })

    const result = await fetchConversations({ limit: 20, offset: 40 })

    expect(http.get).toHaveBeenCalledWith('/chat/conversations', {
      params: {
        limit: 20,
        offset: 40
      }
    })
    expect(result.total).toBe(42)
  })
})
