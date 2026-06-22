import { describe, expect, it } from 'vitest'
import apiSource from '../src/api/agent.ts?raw'
import typeSource from '../src/types/agent.ts?raw'
import viewSource from '../src/views/AgentsView.vue?raw'

describe('Agent 工具绑定弹窗', () => {
  it('定义 Agent 工具绑定和工具选项类型', () => {
    expect(typeSource).toContain('export interface AgentToolBindingRecord')
    expect(typeSource).toContain('export interface AgentToolOptionRecord')
    expect(typeSource).toContain('toolName: string')
    expect(typeSource).toContain('providerType: string')
    expect(typeSource).toContain('providerRefId: number')
    expect(typeSource).toContain('displayName')
    expect(typeSource).toContain('description')
    expect(typeSource).toContain('enabled')
    expect(typeSource).toContain('sortOrder')
    expect(typeSource).toContain('serverName')
    expect(typeSource).toContain('serverEnabled')
    expect(typeSource).toContain('toolEnabled')
  })

  it('提供 Agent 工具绑定 API 封装', () => {
    expect(apiSource).toContain('fetchAgentToolBindings')
    expect(apiSource).toContain('fetchAgentToolOptions')
    expect(apiSource).toContain('updateAgentToolBindings')
    expect(apiSource).toContain('`/agents/${agentId}/tools`')
    expect(apiSource).toContain('`/agents/${agentId}/tools/options`')
    expect(apiSource).toContain('tools: tools.map')
    expect(apiSource).toContain('sortOrder')
  })

  it('页面提供工具按钮和绑定弹窗状态', () => {
    expect(viewSource).toContain("default: () => '工具'")
    expect(viewSource).toContain('openTools(row)')
    expect(viewSource).toContain('showToolModal')
    expect(viewSource).toContain('toolModalAgent')
    expect(viewSource).toContain('toolLoading')
    expect(viewSource).toContain('toolSaving')
    expect(viewSource).toContain('toolKeyword')
    expect(viewSource).toContain('toolOptions')
    expect(viewSource).toContain('selectedToolNames')
  })

  it('工具表格支持搜索、状态标签和不可用项禁选', () => {
    expect(viewSource).toContain('filteredToolOptions')
    expect(viewSource).toContain('toolName')
    expect(viewSource).toContain('displayName')
    expect(viewSource).toContain('description')
    expect(viewSource).toContain('serverName')
    expect(viewSource).toContain('服务启用')
    expect(viewSource).toContain('服务停用')
    expect(viewSource).toContain('工具启用')
    expect(viewSource).toContain('工具停用')
    expect(viewSource).toContain('isToolSelectable')
    expect(viewSource).toContain('disabled: (row) => !isToolSelectable(row)')
  })

  it('保存时只提交可用的选中工具并给出中文提示', () => {
    expect(viewSource).toContain('submitToolBindings')
    expect(viewSource).toContain('updateAgentToolBindings')
    expect(viewSource).toContain('selectedToolNameSet')
    expect(viewSource).toContain('filter((item) => selectedToolNameSet.has(item.toolName) && isToolSelectable(item))')
    expect(viewSource).toContain('enabled: true')
    expect(viewSource).toContain('sortOrder: index')
    expect(viewSource).toContain('Agent 工具绑定已保存')
  })
})
