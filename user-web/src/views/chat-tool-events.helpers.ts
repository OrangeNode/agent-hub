export interface ChatToolTrace {
  toolCallId: string
  name: string
  status: string
  stdout: string
  stderr: string
  result: string
}

export interface ChatReasoningProcessEvent {
  type: 'reasoning'
  content: string
}

export interface ChatToolProcessEvent {
  type: 'tool'
  tool: ChatToolTrace
}

export type ChatProcessEvent = ChatReasoningProcessEvent | ChatToolProcessEvent

export interface ToolCallEvent {
  toolCallId: string
  name: string
}

export interface ToolOutputEvent {
  toolCallId: string
  stream: string
  content: string
}

export interface ToolResultEvent {
  toolCallId: string
  name: string
  status: string
  content: string
}

export function createProcessEvents(): ChatProcessEvent[] {
  return []
}

export function createInitialProcessEvents(
  reasoningContent: string | null | undefined,
  processEvents: ChatProcessEvent[] | null | undefined
) {
  const normalizedEvents = normalizeProcessEvents(processEvents)
  if (normalizedEvents.length > 0) {
    return normalizedEvents
  }

  const events = createProcessEvents()
  appendReasoningEvent(events, reasoningContent ?? '')
  return events
}

export function appendReasoningEvent(events: ChatProcessEvent[], content: string) {
  if (!content) {
    return
  }
  const lastEvent = events[events.length - 1]
  if (lastEvent?.type === 'reasoning') {
    lastEvent.content += content
    return
  }
  events.push({ type: 'reasoning', content })
}

export function applyToolCallEvent(events: ChatProcessEvent[], event: ToolCallEvent) {
  const trace = ensureToolTrace(events, event.toolCallId)
  trace.name = event.name || trace.name
  trace.status = 'running'
}

export function applyToolOutputEvent(events: ChatProcessEvent[], event: ToolOutputEvent) {
  const trace = ensureToolTrace(events, event.toolCallId)
  if (event.stream === 'stderr') {
    trace.stderr += event.content || ''
    return
  }
  trace.stdout += event.content || ''
}

export function applyToolResultEvent(events: ChatProcessEvent[], event: ToolResultEvent) {
  const trace = ensureToolTrace(events, event.toolCallId)
  trace.name = event.name || trace.name
  trace.status = event.status || 'completed'
  trace.result = event.content || ''
}

function ensureToolTrace(events: ChatProcessEvent[], toolCallId: string) {
  const existing = events.find((event): event is ChatToolProcessEvent =>
    event.type === 'tool' && event.tool.toolCallId === toolCallId
  )
  if (existing) {
    return existing.tool
  }

  const trace: ChatToolTrace = {
    toolCallId,
    name: '工具调用',
    status: 'running',
    stdout: '',
    stderr: '',
    result: ''
  }
  events.push({ type: 'tool', tool: trace })
  return trace
}

function normalizeProcessEvents(processEvents: ChatProcessEvent[] | null | undefined) {
  if (!processEvents?.length) {
    return []
  }

  return processEvents
    .map((event) => normalizeProcessEvent(event))
    .filter((event): event is ChatProcessEvent => Boolean(event))
}

function normalizeProcessEvent(event: ChatProcessEvent | null | undefined): ChatProcessEvent | null {
  if (!event) {
    return null
  }
  if (event.type === 'reasoning') {
    return {
      type: 'reasoning',
      content: event.content || ''
    }
  }
  if (event.type !== 'tool' || !event.tool) {
    return null
  }
  return {
    type: 'tool',
    tool: {
      toolCallId: event.tool.toolCallId || '',
      name: event.tool.name || '工具调用',
      status: event.tool.status || 'running',
      stdout: event.tool.stdout || '',
      stderr: event.tool.stderr || '',
      result: event.tool.result || ''
    }
  }
}
