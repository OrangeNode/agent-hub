import { describe, expect, it } from 'vitest'
import { validateImageCount, validateImageFile } from '../src/views/chat-attachments.helpers'
import chatViewSource from '../src/views/ChatView.vue?raw'

function file(name: string, type: string, size: number) {
  return new File([new Uint8Array(size)], name, { type, lastModified: 0 })
}

describe('聊天图片附件校验', () => {
  it('应该允许 jpg/png/webp/gif 图片', () => {
    expect(validateImageFile(file('a.jpg', 'image/jpeg', 1024))).toBeNull()
    expect(validateImageFile(file('a.jpeg', 'image/jpeg', 1024))).toBeNull()
    expect(validateImageFile(file('a.png', 'image/png', 1024))).toBeNull()
    expect(validateImageFile(file('a.webp', 'image/webp', 1024))).toBeNull()
    expect(validateImageFile(file('a.gif', 'image/gif', 1024))).toBeNull()
  })

  it('应该拒绝非图片文件', () => {
    expect(validateImageFile(file('a.pdf', 'application/pdf', 1024))).toBe('仅支持 jpg、jpeg、png、webp、gif 图片')
  })

  it('应该拒绝超过 20MB 的图片', () => {
    expect(validateImageFile(file('a.png', 'image/png', 20 * 1024 * 1024 + 1))).toBe('图片不能超过 20MB')
  })

  it('应该限制单条消息最多 8 张图片', () => {
    expect(validateImageCount(8, 1)).toBe('单条消息最多上传 8 张图片')
    expect(validateImageCount(7, 1)).toBeNull()
  })
  it('聊天主区域应支持粘贴和拖拽图片上传', () => {
    expect(chatViewSource).toContain('@paste="handleImagePaste"')
    expect(chatViewSource).toContain('@drop.prevent="handleImageDrop"')
    expect(chatViewSource).toContain('event.clipboardData?.files')
    expect(chatViewSource).toContain('event.dataTransfer?.files')
  })
})
