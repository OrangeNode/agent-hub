import { describe, expect, it } from 'vitest'
import apiSource from '../src/api/mcp.ts?raw'
import routeSource from '../src/router/route-registry.ts?raw'
import typeSource from '../src/types/mcp.ts?raw'
import viewSource from '../src/views/McpServersView.vue?raw'

describe('MCP 服务管理', () => {
  it('定义 MCP 服务和工具类型', () => {
    expect(typeSource).toContain('export interface McpServerRecord')
    expect(typeSource).toContain('serverKey: string')
    expect(typeSource).toContain('hasAuthToken: boolean')
    expect(typeSource).toContain('export interface McpToolRecord')
    expect(typeSource).toContain('exposedToolName: string')
  })

  it('提供 MCP 管理 API', () => {
    expect(apiSource).toContain("'/mcp/servers'")
    expect(apiSource).toContain('testMcpServer')
    expect(apiSource).toContain('syncMcpServerTools')
    expect(apiSource).toContain('/mcp/tools/${toolId}/enabled')
    expect(apiSource).toContain("'/mcp/calls'")
  })

  it('注册 MCP 服务路由', () => {
    expect(routeSource).toContain("path: '/ai/mcp-servers'")
    expect(routeSource).toContain("name: 'ai-mcp-servers'")
    expect(routeSource).toContain("permission: 'mcp:server:list'")
    expect(routeSource).toContain("component: () => import('../views/McpServersView.vue')")
  })

  it('页面提供测试、同步工具和工具列表操作', () => {
    expect(viewSource).toContain('testMcpServer')
    expect(viewSource).toContain('syncMcpServerTools')
    expect(viewSource).toContain('fetchMcpTools')
    expect(viewSource).toContain("default: () => '测试'")
    expect(viewSource).toContain("default: () => '同步工具'")
    expect(viewSource).toContain("default: () => '工具列表'")
  })

  it('认证 Token 使用密码输入并展示工具暴露名', () => {
    expect(viewSource).toContain('type="password"')
    expect(viewSource).toContain('show-password-on="click"')
    expect(viewSource).toContain('exposedToolName')
  })
})
