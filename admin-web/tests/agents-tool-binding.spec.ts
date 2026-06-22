import { describe, expect, it } from 'vitest'
import apiSource from '../src/api/agent.ts?raw'
import typeSource from '../src/types/agent.ts?raw'
import viewSource from '../src/views/AgentsView.vue?raw'

function extractInterface(name: string) {
  const match = typeSource.match(new RegExp(`export interface ${name} \\{[\\s\\S]*?\\n\\}`))
  expect(match).not.toBeNull()
  return match?.[0] ?? ''
}

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

  it('保存 payload 使用后端真实契约', () => {
    const payloadSource = extractInterface('AgentToolBindingPayload')

    expect(payloadSource).toContain('toolName: string')
    expect(payloadSource).toContain('providerType: string')
    expect(payloadSource).toContain('providerRefId: number')
    expect(payloadSource).toContain('sort: number')
    expect(payloadSource).not.toContain('enabled')
    expect(payloadSource).not.toContain('sortOrder')
  })

  it('提供 Agent 工具绑定 API 封装且只发送后端接收字段', () => {
    expect(apiSource).toContain('fetchAgentToolBindings')
    expect(apiSource).toContain('fetchAgentToolOptions')
    expect(apiSource).toContain('updateAgentToolBindings')
    expect(apiSource).toContain('`/agents/${agentId}/tools`')
    expect(apiSource).toContain('`/agents/${agentId}/tools/options`')
    expect(apiSource).toContain('tools: tools.map')
    expect(apiSource).toContain('sort: item.sort')
    expect(apiSource).not.toContain('enabled: item.enabled')
    expect(apiSource).not.toContain('sortOrder')
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
    expect(viewSource).toContain('toolLoadFailed')
    expect(viewSource).toContain('toolRequestVersion')
  })

  it('工具表格支持搜索、状态标签和不可用项禁选', () => {
    expect(viewSource).toContain('filteredToolOptions')
    expect(viewSource).toContain('toolName')
    expect(viewSource).toContain('displayName')
    expect(viewSource).toContain('description')
    expect(viewSource).toContain('serverName')
    expect(viewSource).toContain('serverKey')
    expect(viewSource).toContain('服务启用')
    expect(viewSource).toContain('服务停用')
    expect(viewSource).toContain('工具启用')
    expect(viewSource).toContain('工具停用')
    expect(viewSource).toContain('isToolSelectable')
    expect(viewSource).toContain('disabled: (row) => !isToolSelectable(row)')
  })

  it('加载失败或加载中不能保存并避免误清空绑定', () => {
    expect(viewSource).toContain(':disabled="toolLoading || toolLoadFailed"')
    expect(viewSource).toContain('toolLoadFailed.value = true')
    expect(viewSource).toContain('if (!toolModalAgent.value || toolLoading.value || toolLoadFailed.value)')
  })

  it('旧弹窗请求失效后不能覆盖当前 Agent 状态', () => {
    expect(viewSource).toContain('@update:show="handleToolModalShowChange"')
    expect(viewSource).toContain('function invalidateToolRequest()')
    expect(viewSource).toContain('function isCurrentToolRequest(requestVersion: number, agentId: number)')
    expect(viewSource).toContain('const requestVersion = ++toolRequestVersion.value')
    expect(viewSource).toContain('if (!isCurrentToolRequest(requestVersion, agent.agentId))')
    expect(viewSource).toContain('function closeToolModal()')
  })

  it('保存完成时只关闭当前 Agent 的弹窗', () => {
    expect(viewSource).toContain('const agentId = toolModalAgent.value.agentId')
    expect(viewSource).toContain('const saveRequestVersion = toolRequestVersion.value')
    expect(viewSource).toContain('await updateAgentToolBindings(agentId, tools)')
    expect(viewSource).toContain('if (isCurrentToolRequest(saveRequestVersion, agentId))')
  })

  it('保存时只提交可用的选中工具并给出中文提示', () => {
    expect(viewSource).toContain('submitToolBindings')
    expect(viewSource).toContain('updateAgentToolBindings')
    expect(viewSource).toContain('selectedToolNameSet')
    expect(viewSource).toContain('filter((item) => selectedToolNameSet.has(item.toolName) && isToolSelectable(item))')
    expect(viewSource).toContain('sort: index')
    expect(viewSource).toContain('Agent 工具绑定已保存')
  })
})
