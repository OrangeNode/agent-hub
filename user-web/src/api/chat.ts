export function openChatStream(prompt: string, token: string): EventSource {
  const url = new URL('/api/chat/stream', window.location.origin)
  url.searchParams.set('prompt', prompt)
  url.searchParams.set('token', token)
  return new EventSource(url)
}
