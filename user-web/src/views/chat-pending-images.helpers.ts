import type { Ref } from 'vue'

export interface PendingImageState {
  localId: string
}

export function updatePendingImageByLocalId<T extends PendingImageState>(
  items: Ref<T[]>,
  localId: string,
  patch: Partial<Omit<T, 'localId'>>
) {
  const index = items.value.findIndex((item) => item.localId === localId)
  if (index < 0) {
    return null
  }

  const nextItem = {
    ...items.value[index],
    ...patch
  }
  items.value.splice(index, 1, nextItem)
  return nextItem
}
