const BEIJING_OFFSET_MINUTES = 8 * 60

type DateInput = string | number | Date

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function toDate(value: DateInput): Date | null {
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

export function toBeijingIsoString(value: DateInput): string {
  const date = toDate(value)
  if (!date) {
    return ''
  }

  const beijingTime = new Date(date.getTime() + BEIJING_OFFSET_MINUTES * 60 * 1000)
  const year = beijingTime.getUTCFullYear()
  const month = pad(beijingTime.getUTCMonth() + 1)
  const day = pad(beijingTime.getUTCDate())
  const hour = pad(beijingTime.getUTCHours())
  const minute = pad(beijingTime.getUTCMinutes())
  const second = pad(beijingTime.getUTCSeconds())
  return `${year}-${month}-${day}T${hour}:${minute}:${second}+08:00`
}

export function formatBeijingDateTime(value?: string | null): string {
  if (!value) {
    return '-'
  }

  const date = toDate(value)
  if (!date) {
    return value
  }

  return toBeijingIsoString(date).slice(0, 19).replace('T', ' ')
}
