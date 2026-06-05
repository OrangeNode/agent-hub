import { computed, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import { updatePendingImageByLocalId } from '../src/views/chat-pending-images.helpers'

interface PendingImageForTest {
  localId: string
  progress: number
  attachmentId?: number
  status: 'uploading' | 'ready' | 'failed'
}

describe('聊天待上传图片状态', () => {
  it('更新上传状态时应触发发送按钮依赖的响应式计算', () => {
    const pendingImages = ref<PendingImageForTest[]>([
      {
        localId: 'image-1',
        progress: 0,
        status: 'uploading'
      }
    ])
    const hasUploadingImage = computed(() =>
      pendingImages.value.some((image) => image.status === 'uploading')
    )

    expect(hasUploadingImage.value).toBe(true)

    updatePendingImageByLocalId(pendingImages, 'image-1', {
      attachmentId: 13,
      progress: 100,
      status: 'ready'
    })

    expect(hasUploadingImage.value).toBe(false)
    expect(pendingImages.value[0]).toMatchObject({
      attachmentId: 13,
      progress: 100,
      status: 'ready'
    })
  })

  it('目标图片已移除时不应修改待上传列表', () => {
    const pendingImages = ref<PendingImageForTest[]>([
      {
        localId: 'image-1',
        progress: 20,
        status: 'uploading'
      }
    ])

    const updatedImage = updatePendingImageByLocalId(pendingImages, 'missing-image', {
      progress: 100,
      status: 'ready'
    })

    expect(updatedImage).toBeNull()
    expect(pendingImages.value).toEqual([
      {
        localId: 'image-1',
        progress: 20,
        status: 'uploading'
      }
    ])
  })
})
