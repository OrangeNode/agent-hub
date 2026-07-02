const maxImageSize = 20 * 1024 * 1024
const maxImageCount = 8
const maxAttachmentSize = 50 * 1024 * 1024
const supportedImageTypesByExtension = new Map([
  ['jpg', 'image/jpeg'],
  ['jpeg', 'image/jpeg'],
  ['png', 'image/png'],
  ['webp', 'image/webp'],
  ['gif', 'image/gif']
])
const supportedAttachmentTypesByExtension = new Map([
  ...supportedImageTypesByExtension,
  ['doc', 'application/msword'],
  ['docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
  ['xls', 'application/vnd.ms-excel'],
  ['xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet']
])

function fileExtension(file: File) {
  return file.name.split('.').pop()?.toLowerCase() ?? ''
}

function normalizeContentType(contentType: string) {
  return contentType.trim().toLowerCase()
}

export function validateImageFile(file: File): string | null {
  const extension = fileExtension(file)
  const contentType = supportedImageTypesByExtension.get(extension)

  if (!contentType || normalizeContentType(file.type) !== contentType) {
    return '仅支持 jpg、jpeg、png、webp、gif 图片'
  }

  if (file.size > maxImageSize) {
    return '图片不能超过 20MB'
  }

  return null
}

export function resolveAttachmentContentType(file: File): string {
  const normalizedType = normalizeContentType(file.type)
  if (normalizedType) {
    return normalizedType
  }
  return supportedAttachmentTypesByExtension.get(fileExtension(file)) ?? ''
}

export function validateChatAttachmentFile(file: File): string | null {
  const extension = fileExtension(file)
  const expectedContentType = supportedAttachmentTypesByExtension.get(extension)
  const contentType = resolveAttachmentContentType(file)

  if (!expectedContentType || contentType !== expectedContentType) {
    return '仅支持图片、Word、Excel 文件'
  }

  if (supportedImageTypesByExtension.has(extension)) {
    return validateImageFile(new File([file], file.name, { type: contentType, lastModified: file.lastModified }))
  }

  if (file.size > maxAttachmentSize) {
    return '文件不能超过 50MB'
  }

  return null
}

export function validateImageCount(currentCount: number, addingCount: number): string | null {
  if (currentCount + addingCount > maxImageCount) {
    return '单条消息最多上传 8 张图片'
  }

  return null
}
