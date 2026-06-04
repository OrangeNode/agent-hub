import { describe, expect, it } from 'vitest'
import { selectReusableImageAttachments, validateReuseImageCount } from '../src/views/ChatView.vue'

describe('历史图片复用', () => {
  it('只允许选择未过期且有预览地址的图片', () => {
    expect(selectReusableImageAttachments([
      { attachmentId: 1, expired: false, previewUrl: 'https://example.com/1.png' },
      { attachmentId: 2, expired: true, previewUrl: 'https://example.com/2.png' },
      { attachmentId: 3, expired: false, previewUrl: null }
    ])).toEqual([
      { attachmentId: 1, expired: false, previewUrl: 'https://example.com/1.png' }
    ])
  })

  it('待上传图片和复用图片合计最多 8 张', () => {
    expect(validateReuseImageCount(7, 1)).toBeNull()
    expect(validateReuseImageCount(7, 2)).toBe('单条消息最多上传 8 张图片')
  })
})
