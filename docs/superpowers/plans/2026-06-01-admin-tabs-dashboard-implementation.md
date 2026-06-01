# 管理端多标签与工作台优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `admin-web` 增加可拖动、多右键操作的多标签栏，并将工作台重做为更美观的企业后台首页。

**Architecture:** 标签行为统一收敛到 `tabs store`，由布局层负责拖拽事件和右键菜单展示；工作台页继续只使用现有登录态与菜单数据，不新增后端依赖。实现顺序采用先测试、后状态层、再视图层、最后回归验证的方式，降低交互改造风险。

**Tech Stack:** Vue 3、TypeScript、Pinia、Vue Router、Naive UI、Vitest

---

## 文件结构

- `admin-web/src/stores/tabs.ts`
  - 标签状态、固定工作台、排序、批量关闭
- `admin-web/src/stores/tabs.helpers.ts`
  - 标签顺序与关闭策略相关工具函数
- `admin-web/src/layouts/AdminLayout.vue`
  - 标签拖拽交互、右键菜单、激活态同步
- `admin-web/src/views/DashboardView.vue`
  - 工作台页面结构与样式重做
- `admin-web/tests/tabs.spec.ts`
  - 标签状态层测试
- `admin-web/tests/dashboard.spec.ts`
  - 工作台派生数据或结构测试

### Task 1: 强化标签状态层

**Files:**
- Modify: `admin-web/src/stores/tabs.ts`
- Modify: `admin-web/src/stores/tabs.helpers.ts`
- Test: `admin-web/tests/tabs.spec.ts`

- [ ] **Step 1: 先写失败测试，覆盖固定工作台与批量关闭**

```ts
it('工作台应始终位于第一位且不可关闭', () => {
  const store = useTabsStore()
  store.addTab({ path: '/dashboard', name: 'dashboard', meta: { title: '工作台' } })
  store.addTab({ path: '/system/users', name: 'system-users', meta: { title: '用户管理' } })

  expect(store.tabs[0].path).toBe('/dashboard')
  expect(store.tabs[0].closable).toBe(false)
})

it('关闭全部后应只保留工作台', () => {
  const store = useTabsStore()
  store.addTab({ path: '/dashboard', name: 'dashboard', meta: { title: '工作台' } })
  store.addTab({ path: '/system/users', name: 'system-users', meta: { title: '用户管理' } })
  store.closeAllTabs()

  expect(store.tabs).toHaveLength(1)
  expect(store.tabs[0].path).toBe('/dashboard')
})
```

- [ ] **Step 2: 运行标签测试，确认新增用例先失败**

Run: `npm test -- tabs.spec.ts`  
Expected: FAIL，提示缺少 `closeAllTabs`、排序或固定标签相关能力

- [ ] **Step 3: 实现最小状态层能力**

```ts
const DASHBOARD_TAB: TabItem = {
  key: 'dashboard',
  title: '工作台',
  path: '/dashboard',
  closable: false
}

function ensureDashboardFirst() {
  const dashboard = tabs.value.find((item) => item.path === '/dashboard') ?? { ...DASHBOARD_TAB }
  const rest = tabs.value.filter((item) => item.path !== '/dashboard')
  tabs.value = [dashboard, ...rest]
}
```

- [ ] **Step 4: 增加排序与批量关闭方法**

```ts
function moveTab(fromPath: string, toPath: string) {
  if (fromPath === '/dashboard' || toPath === '/dashboard') return
}

function closeAllTabs() {
  tabs.value = [{ ...DASHBOARD_TAB }]
  activeTab.value = '/dashboard'
}
```

- [ ] **Step 5: 重跑标签测试并确认通过**

Run: `npm test -- tabs.spec.ts`  
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add admin-web/src/stores/tabs.ts admin-web/src/stores/tabs.helpers.ts admin-web/tests/tabs.spec.ts
git commit -m "增强多标签状态管理"
```

### Task 2: 改造标签栏拖拽与右键菜单

**Files:**
- Modify: `admin-web/src/layouts/AdminLayout.vue`
- Test: `admin-web/tests/tabs.spec.ts`

- [ ] **Step 1: 先补充行为测试用例**

```ts
it('关闭右侧时应保留当前标签左侧及工作台', () => {
  const store = useTabsStore()
  // 添加 dashboard / users / roles / menus
  const nextPath = store.closeTabsToRight('/system/roles')
  expect(store.tabs.map((item) => item.path)).toEqual([
    '/dashboard',
    '/system/users',
    '/system/roles'
  ])
  expect(nextPath).toBe('/system/roles')
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- tabs.spec.ts`  
Expected: FAIL，提示缺少 `closeTabsToRight` 等方法

- [ ] **Step 3: 在布局层接入拖拽与右键菜单**

```vue
<div
  draggable="true"
  @dragstart="handleDragStart(item.path)"
  @dragover.prevent
  @drop="handleDrop(item.path)"
  @contextmenu.prevent="openTabContextMenu($event, item.path)"
>
```

- [ ] **Step 4: 用 Naive UI 下拉菜单承载右键操作**

```ts
const contextMenuOptions = computed(() => [
  { label: '关闭当前', key: 'close-current', disabled: currentContextPath.value === '/dashboard' },
  { label: '关闭其他', key: 'close-others' },
  { label: '关闭右侧', key: 'close-right' },
  { label: '关闭全部', key: 'close-all' }
])
```

- [ ] **Step 5: 重跑标签测试**

Run: `npm test -- tabs.spec.ts`  
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add admin-web/src/layouts/AdminLayout.vue admin-web/tests/tabs.spec.ts
git commit -m "实现多标签拖拽与右键操作"
```

### Task 3: 重做工作台页面

**Files:**
- Modify: `admin-web/src/views/DashboardView.vue`
- Create: `admin-web/tests/dashboard.spec.ts`

- [ ] **Step 1: 先写工作台结构测试**

```ts
it('工作台指标应包含角色数、菜单数、权限点数和活跃标签数', () => {
  const metrics = buildDashboardMetrics(profile, menus, tabs)
  expect(metrics.map((item) => item.key)).toEqual([
    'roles',
    'menus',
    'permissions',
    'tabs'
  ])
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `npm test -- dashboard.spec.ts`  
Expected: FAIL，提示缺少工作台派生逻辑

- [ ] **Step 3: 实现更稳的首页结构**

```vue
<section class="dashboard-hero">...</section>
<section class="metric-grid">...</section>
<section class="quick-entry-grid">...</section>
<section class="overview-grid">...</section>
```

- [ ] **Step 4: 补足样式**

```css
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}
```

- [ ] **Step 5: 跑工作台测试**

Run: `npm test -- dashboard.spec.ts`  
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add admin-web/src/views/DashboardView.vue admin-web/tests/dashboard.spec.ts
git commit -m "重做管理端工作台页面"
```

### Task 4: 全量回归

**Files:**
- Verify only

- [ ] **Step 1: 跑全部单测**

Run: `npm test`  
Expected: PASS

- [ ] **Step 2: 跑类型检查**

Run: `npm run type-check`  
Expected: PASS

- [ ] **Step 3: 跑生产构建**

Run: `npm run build`  
Expected: PASS

- [ ] **Step 4: 浏览器回归验证**

Run: 打开 `http://127.0.0.1:5176`
Expected:
- 标签可拖动
- 工作台固定第一
- 右键菜单可用
- 工作台首页布局更完整

- [ ] **Step 5: 最终提交**

```bash
git add admin-web
git commit -m "完成管理端多标签与工作台优化"
```
