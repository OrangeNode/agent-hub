export function formatReasoningDuration(durationMs: number) {
  const totalSeconds = Math.max(1, Math.ceil(durationMs / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60

  if (minutes <= 0) {
    return `${seconds}s`
  }

  return `${minutes}m ${seconds}s`
}

export function buildReasoningStatusText(durationMs: number, completed: boolean) {
  return `${completed ? '已思考' : '已处理'} ${formatReasoningDuration(durationMs)}`
}
