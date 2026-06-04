const maxImageSize = 20 * 1024 * 1024
const maxImageCount = 8
const supportedImageTypesByExtension = new Map([
  ['jpg', 'image/jpeg'],
  ['jpeg', 'image/jpeg'],
  ['png', 'image/png'],
  ['webp', 'image/webp'],
  ['gif', 'image/gif']
])

export function validateImageFile(file: File): string | null {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  const contentType = supportedImageTypesByExtension.get(extension)

  if (!contentType || file.type !== contentType) {
    return '仅支持 jpg、jpeg、png、webp、gif 图片'
  }

  if (file.size > maxImageSize) {
    return '图片不能超过 20MB'
  }

  return null
}

export function validateImageCount(currentCount: number, addingCount: number): string | null {
  if (currentCount + addingCount > maxImageCount) {
    return '单条消息最多上传 8 张图片'
  }

  return null
}
