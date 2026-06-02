import type { Component } from 'vue'
import {
  ApiOutlined,
  AppstoreOutlined,
  AuditOutlined,
  BarChartOutlined,
  BookOutlined,
  BuildOutlined,
  ClusterOutlined,
  CloudServerOutlined,
  CommentOutlined,
  ControlOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  DesktopOutlined,
  ExperimentOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  KeyOutlined,
  LineChartOutlined,
  MenuOutlined,
  MessageOutlined,
  NotificationOutlined,
  PartitionOutlined,
  ReadOutlined,
  RobotOutlined,
  SafetyOutlined,
  SettingOutlined,
  ShopOutlined,
  TagsOutlined,
  TeamOutlined,
  ToolOutlined,
  UserOutlined
} from '@vicons/antd'

export interface MenuIconOption {
  label: string
  value: string
  component: Component
}

const menuIcons: MenuIconOption[] = [
  { label: 'DashboardOutlined', value: 'DashboardOutlined', component: DashboardOutlined },
  { label: 'AppstoreOutlined', value: 'AppstoreOutlined', component: AppstoreOutlined },
  { label: 'SettingOutlined', value: 'SettingOutlined', component: SettingOutlined },
  { label: 'UserOutlined', value: 'UserOutlined', component: UserOutlined },
  { label: 'TeamOutlined', value: 'TeamOutlined', component: TeamOutlined },
  { label: 'MenuOutlined', value: 'MenuOutlined', component: MenuOutlined },
  { label: 'FileTextOutlined', value: 'FileTextOutlined', component: FileTextOutlined },
  { label: 'NotificationOutlined', value: 'NotificationOutlined', component: NotificationOutlined },
  { label: 'DatabaseOutlined', value: 'DatabaseOutlined', component: DatabaseOutlined },
  { label: 'ApiOutlined', value: 'ApiOutlined', component: ApiOutlined },
  { label: 'ToolOutlined', value: 'ToolOutlined', component: ToolOutlined },
  { label: 'SafetyOutlined', value: 'SafetyOutlined', component: SafetyOutlined },
  { label: 'ClusterOutlined', value: 'ClusterOutlined', component: ClusterOutlined },
  { label: 'ControlOutlined', value: 'ControlOutlined', component: ControlOutlined },
  { label: 'DeploymentUnitOutlined', value: 'DeploymentUnitOutlined', component: DeploymentUnitOutlined },
  { label: 'ReadOutlined', value: 'ReadOutlined', component: ReadOutlined },
  { label: 'BookOutlined', value: 'BookOutlined', component: BookOutlined },
  { label: 'ExperimentOutlined', value: 'ExperimentOutlined', component: ExperimentOutlined },
  { label: 'DesktopOutlined', value: 'DesktopOutlined', component: DesktopOutlined },
  { label: 'CloudServerOutlined', value: 'CloudServerOutlined', component: CloudServerOutlined },
  { label: 'RobotOutlined', value: 'RobotOutlined', component: RobotOutlined },
  { label: 'MessageOutlined', value: 'MessageOutlined', component: MessageOutlined },
  { label: 'CommentOutlined', value: 'CommentOutlined', component: CommentOutlined },
  { label: 'PartitionOutlined', value: 'PartitionOutlined', component: PartitionOutlined },
  { label: 'BuildOutlined', value: 'BuildOutlined', component: BuildOutlined },
  { label: 'FolderOpenOutlined', value: 'FolderOpenOutlined', component: FolderOpenOutlined },
  { label: 'ShopOutlined', value: 'ShopOutlined', component: ShopOutlined },
  { label: 'AuditOutlined', value: 'AuditOutlined', component: AuditOutlined },
  { label: 'BarChartOutlined', value: 'BarChartOutlined', component: BarChartOutlined },
  { label: 'LineChartOutlined', value: 'LineChartOutlined', component: LineChartOutlined },
  { label: 'TagsOutlined', value: 'TagsOutlined', component: TagsOutlined },
  { label: 'KeyOutlined', value: 'KeyOutlined', component: KeyOutlined }
]

export function buildMenuIconOptions() {
  return [...menuIcons]
}

export function filterMenuIcons(keyword: string) {
  const normalized = keyword.trim().toLowerCase()
  if (!normalized) {
    return buildMenuIconOptions()
  }

  return menuIcons.filter((item) => item.value.toLowerCase().includes(normalized))
}

export function resolveMenuIcon(name?: string | null) {
  if (!name) {
    return undefined
  }

  return menuIcons.find((item) => item.value === name)?.component
}
