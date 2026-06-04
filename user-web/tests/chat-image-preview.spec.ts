import { describe, expect, it } from 'vitest'

interface UploadItem {
  id: string
  progress: number
  status: 'uploading' | 'ready' | 'failed'
}

function canSend(items: UploadItem[]) {
  return items.every((item) => item.status === 'ready')
}

function previewSize(count: number) {
  return count === 1 ? 'large' : 'small'
}

describe('聊天图片预览状态', () => {
  it('一张图片使用大预览', () => {
    expect(previewSize(1)).toBe('large')
  })

  it('多张图片使用小预览', () => {
    expect(previewSize(3)).toBe('small')
  })

  it('上传中不允许发送', () => {
    expect(canSend([{ id: '1', progress: 50, status: 'uploading' }])).toBe(false)
  })

  it('全部 ready 才允许发送', () => {
    expect(canSend([{ id: '1', progress: 100, status: 'ready' }])).toBe(true)
  })
})
