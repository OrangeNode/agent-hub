import { describe, expect, it } from 'vitest'
import apiSource from '../src/api/mcp.ts?raw'
import routeSource from '../src/router/route-registry.ts?raw'
import typeSource from '../src/types/mcp.ts?raw'

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
})
