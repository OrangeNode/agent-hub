import { createApp, nextTick } from 'vue'
import { describe, expect, it } from 'vitest'

import ThinkingModeSelect from '../src/views/ThinkingModeSelect.vue'

function mountThinkingModeSelect() {
  const host = document.createElement('div')
  document.body.appendChild(host)

  const app = createApp(ThinkingModeSelect, {
    mode: 'thinking',
    modelLabel: '5.5',
    onSelect: () => undefined,
    onConfigure: () => undefined
  })

  app.mount(host)

  return {
    host,
    unmount: () => {
      app.unmount()
      host.remove()
    }
  }
}

describe('思考模式选择器', () => {
  it('展开后应该保持原有下拉菜单，并通过配置按钮打开设置窗口', async () => {
    const { host, unmount } = mountThinkingModeSelect()

    try {
      host.querySelector<HTMLButtonElement>('.thinking-trigger')?.click()
      await nextTick()

      expect(host.querySelector('.thinking-menu-wrap')?.classList.contains('open')).toBe(true)
      expect(host.querySelector('.thinking-latest')?.textContent).toBe('最新 · 5.5')
      expect(host.querySelector('[data-mode-option="instant"]')?.textContent?.trim()).toBe('Instant')
      expect(host.querySelector('[data-mode-option="thinking"]')?.textContent?.trim()).toBe('Thinking')
      expect(host.querySelector('[data-mode-option="thinking"]')?.classList.contains('active')).toBe(true)
      expect(host.querySelector('.thinking-config-trigger')?.textContent?.trim()).toBe('配置...')
    } finally {
      unmount()
    }
  })
})
