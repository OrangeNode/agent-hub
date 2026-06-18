import { describe, expect, it } from 'vitest'

interface UploadItem {
  id: string
  progress: number
  status: 'uploading' | 'ready' | 'failed'
}

interface UploadMenuOption {
  label: string
  disabled: boolean
}

function canSend(items: UploadItem[]) {
  return items.every((item) => item.status === 'ready')
}

function sendButtonDisabled(streaming: boolean, items: UploadItem[]) {
  void streaming
  return items.some((item) => item.status !== 'ready')
}

function previewSize(count: number) {
  return count === 1 ? 'large' : 'small'
}

function uploadMenuOption(imageInputSupported: boolean): UploadMenuOption {
  return {
    label: imageInputSupported ? '上传照片和文件' : '上传照片和文件（当前模型不支持图片输入）',
    disabled: !imageInputSupported
  }
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

  it('上传中或失败图片禁用发送按钮且业务校验不允许发送', () => {
    const items: UploadItem[] = [
      { id: '1', progress: 50, status: 'uploading' },
      { id: '2', progress: 100, status: 'failed' }
    ]

    expect(sendButtonDisabled(false, items)).toBe(true)
    expect(canSend(items)).toBe(false)
  })

  it('流式响应中不禁用发送按钮以保留中止入口', () => {
    expect(sendButtonDisabled(true, [{ id: '1', progress: 100, status: 'ready' }])).toBe(false)
  })

  it('不支持图片时菜单项禁用并展示原因', () => {
    expect(uploadMenuOption(false)).toEqual({
      label: '上传照片和文件（当前模型不支持图片输入）',
      disabled: true
    })
  })

  it('支持图片时菜单项文案保持不变', () => {
    expect(uploadMenuOption(true)).toEqual({
      label: '上传照片和文件',
      disabled: false
    })
  })
})
