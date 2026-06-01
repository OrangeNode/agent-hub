import { describe, expect, it } from 'vitest'
import { buildMenuIconOptions, filterMenuIcons } from '../src/views/menu-icon-registry'

describe('菜单图标注册表', () => {
  it('应该包含常用的 Ant Design 图标选项', () => {
    const options = buildMenuIconOptions()
    const values = options.map((item) => item.value)

    expect(values).toContain('DashboardOutlined')
    expect(values).toContain('SettingOutlined')
    expect(values).toContain('UserOutlined')
    expect(values).toContain('MenuOutlined')
  })

  it('应该支持按图标名称模糊搜索', () => {
    const result = filterMenuIcons('user')

    expect(result.map((item) => item.value)).toContain('UserOutlined')
  })
})
