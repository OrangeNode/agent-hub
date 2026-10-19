import { computed, ref } from 'vue'
import type { Ref } from 'vue'

import {
  confirmAttachmentUpload,
  confirmImageUpload,
  createAttachmentUploadUrl,
  createImageUploadUrl
} from '../../api/chat'
import type { AvailableModelRecord } from '../../api/chat'
import {
  resolveAttachmentContentType,
  validateChatAttachmentFile,
  validateImageCount
} from '../chat-attachments.helpers'
import { updatePendingImageByLocalId } from '../chat-pending-images.helpers'
import { selectReusableImageAttachments, validateReuseImageCount } from '../chat-view.helpers'
import type { ChatAttachmentItem, ChatMessageItem, ChatNotifier, PendingImage } from './chat-types'

interface UseChatAttachmentsOptions {
  selectedModelItem: Ref<AvailableModelRecord | null>
  currentMessages: Ref<ChatMessageItem[]>
  notify: ChatNotifier
}

/**
 * 输入框附件：本地待上传文件、复用历史图片、图片预览，以及发送后本地预览地址的回收。
 */
export function useChatAttachments({ selectedModelItem, currentMessages, notify }: UseChatAttachmentsOptions) {
  const pendingImages = ref<PendingImage[]>([])
  const imagePreviewUrl = ref<string | null>(null)
  const historyImagePickerOpen = ref(false)
  const selectedReuseAttachmentIds = ref<number[]>([])
  // 已随消息发出的本地预览地址，等服务端消息刷新后再回收，避免消息里的图片闪烁。
  let sentLocalPreviewUrls = new Set<string>()

  const hasUploadingImage = computed(() => pendingImages.value.some((image) => image.status === 'uploading'))
  const hasFailedImage = computed(() => pendingImages.value.some((image) => image.status === 'failed'))
  const reusableHistoryAttachments = computed(() => {
    const seenAttachmentIds = new Set<number>()
    const attachments = selectReusableImageAttachments(
      currentMessages.value.flatMap((item) => item.attachments ?? [])
    )

    return attachments.filter((item) => {
      if (seenAttachmentIds.has(item.attachmentId)) {
        return false
      }
      seenAttachmentIds.add(item.attachmentId)
      return true
    })
  })
  const selectedReuseAttachments = computed(() => {
    const selectedIds = new Set(selectedReuseAttachmentIds.value)
    return reusableHistoryAttachments.value.filter((item) => selectedIds.has(item.attachmentId))
  })
  const pendingImageCount = computed(() => pendingImages.value.filter((item) => item.kind === 'image').length)
  const composerImageCount = computed(() => pendingImageCount.value + selectedReuseAttachments.value.length)
  const composerAttachmentCount = computed(() => pendingImages.value.length + selectedReuseAttachments.value.length)
  const historyImageReuseDisabledReason = computed(() => {
    if (!selectedModelItem.value?.imageInputSupported) {
      return '当前模型不支持图片输入'
    }

    if (!reusableHistoryAttachments.value.length) {
      return '当前会话暂无可复用图片'
    }

    return ''
  })

  function handleImageSelected(event: Event) {
    const input = event.target as HTMLInputElement
    const files = Array.from(input.files ?? [])

    enqueueImageFiles(files)
    input.value = ''
  }

  function handleImagePaste(event: ClipboardEvent) {
    const files = Array.from(event.clipboardData?.files ?? [])
      .filter((file) => file.type.startsWith('image/'))

    if (!files.length) {
      return
    }

    event.preventDefault()
    enqueueImageFiles(files)
  }

  function handleImageDrop(event: DragEvent) {
    const files = Array.from(event.dataTransfer?.files ?? [])
      .filter((file) => file.type.startsWith('image/'))

    if (!files.length) {
      return
    }

    enqueueImageFiles(files)
  }

  function enqueueImageFiles(files: File[]) {
    if (!files.length) {
      return
    }

    const acceptedFiles = files
      .map((file) => ({ file, contentType: resolveAttachmentContentType(file) }))
      .filter(({ file, contentType }) => {
        const fileError = validateChatAttachmentFile(file)
        if (fileError) {
          notify.warning(fileError)
          return false
        }
        if (contentType.startsWith('image/') && !selectedModelItem.value?.imageInputSupported) {
          notify.warning('当前模型不支持图片输入')
          return false
        }
        return true
      })
    if (!acceptedFiles.length) {
      return
    }

    const addingImageCount = acceptedFiles.filter((item) => item.contentType.startsWith('image/')).length
    const countError = validateImageCount(composerImageCount.value, addingImageCount)
    if (countError) {
      notify.warning(countError)
      return
    }

    for (const { file, contentType } of acceptedFiles) {
      const isImage = contentType.startsWith('image/')
      const item: PendingImage = {
        localId: createLocalImageId(),
        file,
        kind: isImage ? 'image' : 'file',
        contentType,
        previewUrl: isImage ? URL.createObjectURL(file) : null,
        progress: 0,
        status: 'uploading'
      }
      pendingImages.value.push(item)
      void uploadImage(item.localId)
    }
  }

  function openHistoryImagePicker() {
    if (historyImageReuseDisabledReason.value) {
      notify.warning(historyImageReuseDisabledReason.value)
      return
    }

    historyImagePickerOpen.value = true
  }

  function isReuseAttachmentSelected(attachmentId: number) {
    return selectedReuseAttachmentIds.value.includes(attachmentId)
  }

  function toggleReuseAttachment(attachmentId: number) {
    if (isReuseAttachmentSelected(attachmentId)) {
      removeSelectedReuseAttachment(attachmentId)
      return
    }

    const attachment = reusableHistoryAttachments.value.find((item) => item.attachmentId === attachmentId)
    if (!attachment) {
      notify.warning('只能选择未过期图片')
      return
    }

    const countError = validateReuseImageCount(pendingImageCount.value, selectedReuseAttachmentIds.value.length + 1)
    if (countError) {
      notify.warning(countError)
      return
    }

    selectedReuseAttachmentIds.value = [...selectedReuseAttachmentIds.value, attachmentId]
  }

  async function uploadImage(localId: string) {
    const item = pendingImages.value.find((image) => image.localId === localId)
    if (!item) {
      return
    }

    try {
      const signed = item.kind === 'image'
        ? await createImageUploadUrl(item.file.name, item.contentType, item.file.size)
        : await createAttachmentUploadUrl(item.file.name, item.contentType, item.file.size)
      if (!updatePendingImageByLocalId(pendingImages, localId, { attachmentId: signed.attachmentId })) {
        return
      }
      await uploadImageFile(signed.uploadUrl, signed.headers, item.file, (progress) => {
        updatePendingImageByLocalId(pendingImages, localId, { progress })
      })
      if (!pendingImages.value.some((image) => image.localId === localId)) {
        return
      }
      if (item.kind === 'image') {
        await confirmImageUpload(signed.attachmentId)
      } else {
        await confirmAttachmentUpload(signed.attachmentId)
      }
      updatePendingImageByLocalId(pendingImages, localId, {
        status: 'ready',
        progress: 100
      })
    } catch {
      updatePendingImageByLocalId(pendingImages, localId, {
        status: 'failed',
        error: item.kind === 'image' ? '图片上传失败，请重试' : '文件上传失败，请重试'
      })
    }
  }

  function removePendingImage(localId: string) {
    const image = pendingImages.value.find((item) => item.localId === localId)
    if (!image) {
      return
    }

    revokePendingImageUrl(image)
    pendingImages.value = pendingImages.value.filter((item) => item.localId !== localId)
  }

  function removeSelectedReuseAttachment(attachmentId: number) {
    selectedReuseAttachmentIds.value = selectedReuseAttachmentIds.value.filter((item) => item !== attachmentId)
  }

  function clearSelectedReuseAttachments() {
    selectedReuseAttachmentIds.value = []
    historyImagePickerOpen.value = false
  }

  function clearPendingImages() {
    for (const image of pendingImages.value) {
      revokePendingImageUrl(image)
    }
    pendingImages.value = []
    imagePreviewUrl.value = null
  }

  /**
   * 发送时把待上传列表交给消息，本地预览地址延后到 revokeSentPreviewUrls 回收。
   */
  function takeReadyAttachmentsForSend() {
    const readyImages = pendingImages.value.filter(
      (image) => image.status === 'ready' && typeof image.attachmentId === 'number'
    )
    readyImages
      .filter((image) => image.kind === 'image' && image.previewUrl)
      .forEach((image) => sentLocalPreviewUrls.add(image.previewUrl as string))
    const reuseAttachments = selectedReuseAttachments.value.map((attachment) => ({ ...attachment }))
    pendingImages.value = []
    clearSelectedReuseAttachments()
    return { readyImages, reuseAttachments }
  }

  function revokeSentPreviewUrls() {
    for (const previewUrl of sentLocalPreviewUrls) {
      if (imagePreviewUrl.value === previewUrl) {
        imagePreviewUrl.value = null
      }
      URL.revokeObjectURL(previewUrl)
    }
    sentLocalPreviewUrls = new Set<string>()
  }

  function revokePendingImageUrl(image: PendingImage) {
    if (!image.previewUrl) {
      return
    }
    if (imagePreviewUrl.value === image.previewUrl) {
      imagePreviewUrl.value = null
    }
    URL.revokeObjectURL(image.previewUrl)
  }

  function handleImagePreviewVisibleChange(show: boolean) {
    if (!show) {
      imagePreviewUrl.value = null
    }
  }

  function previewImage(url: string | null | undefined) {
    imagePreviewUrl.value = url ?? null
  }

  function previewMessageAttachment(attachment: ChatAttachmentItem) {
    if (!canPreviewAttachment(attachment)) {
      return
    }

    imagePreviewUrl.value = attachment.previewUrl ?? null
  }

  return {
    pendingImages,
    imagePreviewUrl,
    historyImagePickerOpen,
    selectedReuseAttachmentIds,
    hasUploadingImage,
    hasFailedImage,
    reusableHistoryAttachments,
    selectedReuseAttachments,
    composerImageCount,
    composerAttachmentCount,
    historyImageReuseDisabledReason,
    handleImageSelected,
    handleImagePaste,
    handleImageDrop,
    openHistoryImagePicker,
    isReuseAttachmentSelected,
    toggleReuseAttachment,
    removePendingImage,
    removeSelectedReuseAttachment,
    clearSelectedReuseAttachments,
    clearPendingImages,
    takeReadyAttachmentsForSend,
    revokeSentPreviewUrls,
    handleImagePreviewVisibleChange,
    previewImage,
    previewMessageAttachment
  }
}

export function canPreviewAttachment(attachment: ChatAttachmentItem) {
  return !attachment.expired && isImageContentType(attachment.contentType) && Boolean(attachment.previewUrl)
}

function isImageContentType(contentType?: string | null) {
  return (contentType ?? '').toLowerCase().startsWith('image/')
}

function createLocalImageId() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function uploadImageFile(uploadUrl: string, headers: Record<string, string>, file: File, onProgress: (progress: number) => void) {
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', uploadUrl)

    for (const [key, value] of Object.entries(headers)) {
      xhr.setRequestHeader(key, value)
    }

    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable || event.total <= 0) {
        return
      }
      onProgress(Math.min(99, Math.max(1, Math.round((event.loaded / event.total) * 100))))
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve()
        return
      }
      reject(new Error(`图片上传失败：${xhr.status}`))
    }
    xhr.onerror = () => reject(new Error('图片上传失败'))
    xhr.send(file)
  })
}
