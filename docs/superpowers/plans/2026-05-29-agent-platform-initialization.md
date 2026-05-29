# RBAC 管理端对齐实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成一套可交付的 RBAC 管理闭环，让 `admin-web` 的页面与交互对齐参考项目，同时由现有 Java 后端提供登录、用户、角色、菜单和动态菜单能力，并补齐依赖服务 Docker 部署目录。

**Architecture:** `admin-web` 采用“布局组件 + 路由 + store + API 模块 + RBAC 页面”的前端结构，对齐参考项目的主框架和页面组织，但不照搬其后端接口。`backend` 保持现有多模块架构，在 `system/security/server` 三个方向补齐最小可运行闭环，数据层优先使用内存实现与清晰服务边界。根目录新增 `docker/` 目录承载 PostgreSQL 与 Redis 的部署文件。

**Tech Stack:** Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、Axios、Spring Boot 4.0.6、JDK 21、Maven、Spring Security、JWT、PostgreSQL、Redis、Docker Compose。

---

## 范围检查

本计划只覆盖以下内容：

- `admin-web` 对齐参考项目的后台壳子、动态菜单、标签页和 RBAC 页面
- `backend` 的登录、用户、角色、菜单、当前用户菜单树接口
- `docker/` 依赖服务目录
- 最终联调与构建验证

本计划明确不包含：

- `user-web` 改造
- AI、Agent、MCP、Skill 管理页对齐
- SSE 事件协议设计
- 后端实现方式对齐参考项目

## 文件结构

本轮主要修改或新增以下文件：

```text
Agent-Hub/
  admin-web/
    package.json
    src/
      App.vue
      main.ts
      api/
        auth.ts
        http.ts
        menu.ts
        role.ts
        user.ts
      components/
        layout/
          AppLayout.vue
          HeaderBar.vue
          SidebarMenu.vue
          TabBar.vue
      constants/
        icons.ts
      router/
        index.ts
        route-meta.d.ts
      stores/
        auth.ts
        menu.ts
        tabs.ts
      styles/
        global.css
        theme.ts
      types/
        auth.ts
        menu.ts
        role.ts
        user.ts
      views/
        dashboard/
          index.vue
        login/
          index.vue
        system/
          users.vue
          roles.vue
          menus.vue
  backend/
    agent-hub-system/
      src/main/java/com/agenthub/system/
        auth/
        menu/
        role/
        user/
      src/test/java/com/agenthub/system/
        menu/
        role/
        user/
    agent-hub-server/
      src/main/java/com/agenthub/server/
        auth/
        config/
        system/
      src/test/java/com/agenthub/server/system/
    agent-hub-server/src/main/resources/application.yml
  docker/
    docker-compose.yml
    env/
      postgres.env
      redis.env
    README.md
  README.md
```

## 任务 1：重构 `admin-web` 的后台壳子与主题

**Files:**
- Modify: `C:\Users\Orange\Documents\Agent-Hub\admin-web\package.json`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\App.vue`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\main.ts`
- Delete: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\layouts\AdminLayout.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\components\layout\AppLayout.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\components\layout\HeaderBar.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\components\layout\SidebarMenu.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\components\layout\TabBar.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\styles\theme.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\styles\global.css`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\constants\icons.ts`

- [ ] **Step 1: 补齐布局所需依赖并写出红灯**

把 `admin-web/package.json` 的依赖补到以下最小集合：

```json
{
  "dependencies": {
    "@vicons/ionicons5": "^0.13.0",
    "axios": "^1.9.0",
    "naive-ui": "^2.40.1",
    "pinia": "^3.0.2",
    "vue": "^3.5.13",
    "vue-router": "^4.5.1"
  }
}
```

将 `src/main.ts` 改为先引入不存在的主题文件：

```ts
import './styles/global.css'
```

Run: `cd C:\Users\Orange\Documents\Agent-Hub\admin-web; npm install; npm run type-check`

Expected: FAIL，报错找不到 `./styles/global.css` 或新的布局组件文件。

- [ ] **Step 2: 实现主题入口与应用根组件**

`src/App.vue` 调整为：

```vue
<template>
  <n-config-provider :theme-overrides="glacierLightTheme">
    <n-global-style />
    <router-view />
  </n-config-provider>
</template>

<script setup lang="ts">
import { NConfigProvider, NGlobalStyle } from 'naive-ui'
import { glacierLightTheme } from './styles/theme'
</script>
```

`src/styles/theme.ts` 至少导出：

```ts
import type { GlobalThemeOverrides } from 'naive-ui'

export const glacierLightTheme: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2563eb',
    primaryColorHover: '#1d4ed8',
    primaryColorPressed: '#1e40af',
    borderRadius: '8px',
    fontWeightStrong: '600'
  },
  Layout: {
    color: '#f5f7fb',
    siderColor: '#ffffff',
    headerColor: '#ffffff'
  }
}
```

`src/styles/global.css` 至少定义：

```css
:root {
  --color-bg: #f5f7fb;
  --color-card: #ffffff;
  --color-border: #e5e7eb;
  --color-text: #1f2937;
  --color-text-2: #6b7280;
}

html,
body,
#app {
  margin: 0;
  min-height: 100%;
  background: var(--color-bg);
}

body {
  color: var(--color-text);
  font-family: Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
}
```

- [ ] **Step 3: 实现后台壳子布局组件**

`src/components/layout/AppLayout.vue` 采用 `NLayout` 结构，内容至少包含：

```vue
<template>
  <n-layout has-sider class="app-layout">
    <n-layout-sider :width="220" bordered collapse-mode="width">
      <SidebarMenu />
    </n-layout-sider>
    <n-layout>
      <HeaderBar />
      <TabBar />
      <n-layout-content content-style="padding: 16px;">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>
```

`HeaderBar.vue` 至少包含面包屑和用户下拉；`SidebarMenu.vue` 先使用空菜单 store 占位；`TabBar.vue` 先使用空 tabs store 占位。组件注释和文案全部使用中文。

- [ ] **Step 4: 运行绿灯**

Run: `cd C:\Users\Orange\Documents\Agent-Hub\admin-web; npm run type-check`

Expected: PASS，说明主题、布局和基础依赖已经接好。

- [ ] **Step 5: 提交后台壳子**

```powershell
git add admin-web/package.json admin-web/src/App.vue admin-web/src/main.ts admin-web/src/components admin-web/src/styles admin-web/src/constants
git commit -m "feat: align admin layout shell"
```

## 任务 2：重做 `admin-web` 路由、认证与动态菜单状态

**Files:**
- Modify: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\router\index.ts`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\router\route-meta.d.ts`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\stores\auth.ts`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\api\auth.ts`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\api\http.ts`
- Delete: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\config\module-routes.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\api\menu.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\stores\menu.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\stores\tabs.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\types\auth.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\types\menu.ts`

- [ ] **Step 1: 先写路由与 store 红灯**

将 `src/router/index.ts` 改为引用尚未创建的组件与 store：

```ts
import AppLayout from '../components/layout/AppLayout.vue'
import { useMenuStore } from '../stores/menu'
import { useTabsStore } from '../stores/tabs'
```

声明路由：

```ts
{
  path: '/',
  component: AppLayout,
  redirect: '/dashboard',
  children: [
    { path: 'dashboard', component: () => import('../views/dashboard/index.vue') },
    { path: 'system/users', component: () => import('../views/system/users.vue') },
    { path: 'system/roles', component: () => import('../views/system/roles.vue') },
    { path: 'system/menus', component: () => import('../views/system/menus.vue') }
  ]
}
```

Run: `cd C:\Users\Orange\Documents\Agent-Hub\admin-web; npm run type-check`

Expected: FAIL，报错缺少 `menu.ts`、`tabs.ts`、新页面或新类型文件。

- [ ] **Step 2: 实现认证与 API 基础类型**

`src/types/auth.ts` 至少定义：

```ts
export interface LoginPayload {
  username: string
  password: string
}

export interface LoginUserInfo {
  userId: number
  username: string
  nickname: string
  roles: string[]
  permissions: string[]
}

export interface LoginResult {
  token: string
  userInfo: LoginUserInfo
}
```

`src/api/http.ts` 统一处理 token 注入和 401 跳转，`src/api/auth.ts` 提供：

```ts
export function login(payload: LoginPayload)
export function getCurrentUser()
```

`src/stores/auth.ts` 至少暴露：

```ts
token
userInfo
isAuthenticated
loginAction
logout
restoreSession
```

- [ ] **Step 3: 实现动态菜单与标签页 store**

`src/types/menu.ts` 至少定义：

```ts
export interface MenuItem {
  id: number
  parentId: number | null
  name: string
  title: string
  path: string
  component?: string
  icon?: string
  permission?: string
  sort: number
  visible: boolean
  children?: MenuItem[]
}
```

`src/api/menu.ts` 提供：

```ts
export function getMenuTree()
export function getCurrentUserMenus()
```

`src/stores/menu.ts` 至少暴露：

```ts
menus
menuOptions
fetchCurrentUserMenus
clearMenus
```

`src/stores/tabs.ts` 至少暴露：

```ts
tabs
activeTab
addTab
removeTab
setActiveTab
resetTabs
```

- [ ] **Step 4: 路由守卫接入登录态、菜单和标签页**

在 `src/router/index.ts` 中补充守卫逻辑：

```ts
router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  const menuStore = useMenuStore()

  if (to.path === '/login') {
    return true
  }

  authStore.restoreSession()
  if (!authStore.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (!menuStore.menus.length) {
    await menuStore.fetchCurrentUserMenus()
  }

  return true
})
```

在 `afterEach` 中写入 tabs store。

- [ ] **Step 5: 运行绿灯**

Run: `cd C:\Users\Orange\Documents\Agent-Hub\admin-web; npm run type-check`

Expected: PASS，说明路由、认证、菜单和标签页基础状态已连通。

- [ ] **Step 6: 提交路由与状态管理**

```powershell
git add admin-web/src/router admin-web/src/stores admin-web/src/api admin-web/src/types
git commit -m "feat: add admin auth menu and tabs state"
```

## 任务 3：实现 `admin-web` 登录页、工作台与 RBAC 页面

**Files:**
- Delete: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\LoginView.vue`
- Delete: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\DashboardView.vue`
- Delete: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\ModuleTableView.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\api\user.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\api\role.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\types\user.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\types\role.ts`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\login\index.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\dashboard\index.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\system\users.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\system\roles.vue`
- Create: `C:\Users\Orange\Documents\Agent-Hub\admin-web\src\views\system\menus.vue`

- [ ] **Step 1: 写页面红灯**

先在 `router/index.ts` 中把旧页面引用全部换成新路径：

```ts
() => import('../views/login/index.vue')
() => import('../views/dashboard/index.vue')
() => import('../views/system/users.vue')
() => import('../views/system/roles.vue')
() => import('../views/system/menus.vue')
```

Run: `cd C:\Users\Orange\Documents\Agent-Hub\admin-web; npm run type-check`

Expected: FAIL，报错找不到这些新页面文件。

- [ ] **Step 2: 实现登录页与工作台**

`src/views/login/index.vue` 使用卡片式登录表单，字段为“用户名”“密码”，调用 `authStore.loginAction`。  
`src/views/dashboard/index.vue` 使用简洁工作台，至少展示欢迎语、当前用户角色和三个统计卡片。

登录页提交核心逻辑至少包含：

```ts
const handleLogin = async () => {
  await authStore.loginAction(formValue)
  router.push((route.query.redirect as string) || '/dashboard')
}
```

- [ ] **Step 3: 实现用户管理与角色管理页面**

`src/types/user.ts` 至少定义列表项、表单项和状态字段；`src/api/user.ts` 提供：

```ts
export function getUserPage()
export function createUser(payload: UserForm)
export function updateUser(userId: number, payload: UserForm)
export function deleteUser(userId: number)
export function updateUserStatus(userId: number, enabled: boolean)
```

`src/views/system/users.vue` 采用：

```text
查询表单
新增按钮
NDataTable
编辑弹窗
角色选择
状态开关
```

`src/api/role.ts` 提供列表、创建、更新、删除、菜单绑定接口；`src/views/system/roles.vue` 采用与用户页一致的组织方式。

- [ ] **Step 4: 实现菜单管理页面**

`src/views/system/menus.vue` 使用树表或树列表组织，至少支持：

```text
显示菜单树
新增同级菜单
新增子菜单
编辑菜单
删除菜单
调整排序值
```

页面调用 `menu.ts` 中补充的以下函数：

```ts
getMenuTree
createMenu
updateMenu
deleteMenu
updateMenuOrder
```

- [ ] **Step 5: 运行绿灯**

Run: `cd C:\Users\Orange\Documents\Agent-Hub\admin-web; npm run type-check; npm run build`

Expected: PASS，说明主要页面都已经可编译构建。

- [ ] **Step 6: 提交 RBAC 页面**

```powershell
git add admin-web/src/views admin-web/src/api admin-web/src/types
git commit -m "feat: add admin rbac management pages"
```

## 任务 4：补强 `backend` 的 RBAC 领域模型与内存服务

**Files:**
- Modify: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\menu\MenuItem.java`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\role\RoleItem.java`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\user\UserProfile.java`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\auth\InMemoryAuthService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\menu\MenuCommand.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\menu\MenuService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\menu\InMemoryMenuService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\role\RoleCommand.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\role\RoleService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\role\InMemoryRoleService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\user\UserCommand.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\user\UserService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\main\java\com\agenthub\system\user\InMemoryUserService.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\test\java\com\agenthub\system\menu\InMemoryMenuServiceTest.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\test\java\com\agenthub\system\role\InMemoryRoleServiceTest.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-system\src\test\java\com\agenthub\system\user\InMemoryUserServiceTest.java`

- [ ] **Step 1: 先写内存服务测试**

`InMemoryMenuServiceTest.java` 至少覆盖：

```java
@Test
void shouldReturnCurrentUserMenusByPermissions()

@Test
void shouldCreateAndUpdateMenu()
```

`InMemoryRoleServiceTest.java` 至少覆盖：

```java
@Test
void shouldListRolesAndBindMenus()
```

`InMemoryUserServiceTest.java` 至少覆盖：

```java
@Test
void shouldCreateUpdateAndDisableUser()
```

Run: `cd C:\Users\Orange\Documents\Agent-Hub\backend; mvn -pl agent-hub-system -am test`

Expected: FAIL，报错缺少新的服务、命令对象或方法。

- [ ] **Step 2: 扩展领域对象与命令模型**

把 `UserProfile`、`RoleItem`、`MenuItem` 扩展为能支撑页面展示和编辑的字段，至少包含：

```text
UserProfile: userId, username, nickname, enabled, roles, permissions
RoleItem: roleId, roleCode, roleName, enabled, menuIds
MenuItem: id, parentId, name, title, path, component, icon, permission, sort, visible, children
```

新增 `UserCommand`、`RoleCommand`、`MenuCommand` 作为创建/更新命令对象。

- [ ] **Step 3: 实现用户、角色、菜单内存服务**

`UserService` 至少定义：

```java
List<UserProfile> listUsers();
UserProfile createUser(UserCommand command);
UserProfile updateUser(Long userId, UserCommand command);
void deleteUser(Long userId);
UserProfile updateUserStatus(Long userId, boolean enabled);
```

`RoleService` 至少定义：

```java
List<RoleItem> listRoles();
RoleItem createRole(RoleCommand command);
RoleItem updateRole(Long roleId, RoleCommand command);
void deleteRole(Long roleId);
RoleItem bindMenus(Long roleId, List<Long> menuIds);
```

`MenuService` 至少定义：

```java
List<MenuItem> getMenuTree();
List<MenuItem> getCurrentUserMenus(Long userId, List<String> permissions);
MenuItem createMenu(MenuCommand command);
MenuItem updateMenu(Long menuId, MenuCommand command);
void deleteMenu(Long menuId);
void updateMenuOrder(List<Long> orderedIds);
```

- [ ] **Step 4: 让认证服务与 RBAC 数据联动**

修改 `InMemoryAuthService`，登录成功后返回的 `permissions` 和 `roles` 要与新 `UserService / RoleService / MenuService` 的初始化数据保持一致，至少保证管理员能看到全部 RBAC 菜单。

- [ ] **Step 5: 运行绿灯**

Run: `cd C:\Users\Orange\Documents\Agent-Hub\backend; mvn -pl agent-hub-system -am test`

Expected: PASS，系统模块测试通过。

- [ ] **Step 6: 提交系统服务补强**

```powershell
git add backend/agent-hub-system
git commit -m "feat: add rbac in-memory services"
```

## 任务 5：补齐 `backend` 的 RBAC 控制器、安全配置与接口测试

**Files:**
- Modify: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\config\AppBeans.java`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\config\SecurityConfig.java`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\auth\AuthController.java`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\system\SystemController.java`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\resources\application.yml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\system\UserRequest.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\system\RoleRequest.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\main\java\com\agenthub\server\system\MenuRequest.java`
- Create: `C:\Users\Orange\Documents\Agent-Hub\backend\agent-hub-server\src\test\java\com\agenthub\server\system\SystemControllerTest.java`

- [ ] **Step 1: 先写接口红灯测试**

`SystemControllerTest.java` 至少覆盖：

```java
@Test
void shouldReturnCurrentUserMenus()

@Test
void shouldCreateUser()

@Test
void shouldBindRoleMenus()
```

测试方式使用 `MockMvcBuilders.standaloneSetup(...)`，接入 `GlobalExceptionHandler`，避免依赖完整切片环境。

Run: `cd C:\Users\Orange\Documents\Agent-Hub\backend; mvn -pl agent-hub-server -am test`

Expected: FAIL，报错控制器方法、请求对象或 bean 不存在。

- [ ] **Step 2: 注册新的系统服务 Bean**

在 `AppBeans.java` 中补充：

```java
@Bean
UserService userService()

@Bean
RoleService roleService()

@Bean
MenuService menuService()
```

确保 `AuthService` 和其他现有 bean 不被破坏。

- [ ] **Step 3: 扩展认证与系统接口**

`AuthController` 保持：

```text
POST /api/auth/login
GET /api/auth/me
```

`SystemController` 至少补成：

```text
GET /api/system/users
POST /api/system/users
PUT /api/system/users/{userId}
DELETE /api/system/users/{userId}
PATCH /api/system/users/{userId}/status
GET /api/system/roles
POST /api/system/roles
PUT /api/system/roles/{roleId}
DELETE /api/system/roles/{roleId}
PUT /api/system/roles/{roleId}/menus
GET /api/system/menus/tree
GET /api/system/menus/current
POST /api/system/menus
PUT /api/system/menus/{menuId}
DELETE /api/system/menus/{menuId}
PUT /api/system/menus/order
```

所有返回值继续使用 `ApiResponse`。

- [ ] **Step 4: 调整安全放行与鉴权**

`SecurityConfig.java` 至少确保：

```text
/api/auth/login 允许匿名访问
/api/** 默认需要认证
```

保留 JWT 解析逻辑，普通 RBAC 接口只要求已登录即可；更细的权限字符串校验后续再增强。

- [ ] **Step 5: 运行绿灯**

Run: `cd C:\Users\Orange\Documents\Agent-Hub\backend; mvn test`

Expected: PASS，后端所有测试通过。

- [ ] **Step 6: 提交后端接口闭环**

```powershell
git add backend/agent-hub-server
git commit -m "feat: add backend rbac api endpoints"
```

## 任务 6：新增 `docker/` 依赖服务目录与项目文档

**Files:**
- Create: `C:\Users\Orange\Documents\Agent-Hub\docker\docker-compose.yml`
- Create: `C:\Users\Orange\Documents\Agent-Hub\docker\env\postgres.env`
- Create: `C:\Users\Orange\Documents\Agent-Hub\docker\env\redis.env`
- Create: `C:\Users\Orange\Documents\Agent-Hub\docker\README.md`
- Modify: `C:\Users\Orange\Documents\Agent-Hub\README.md`

- [ ] **Step 1: 写 docker 目录红灯**

先在根目录 `README.md` 中引用不存在的目录：

```md
依赖服务部署说明见 `docker/README.md`。
```

Run: `cd C:\Users\Orange\Documents\Agent-Hub; Test-Path docker\README.md`

Expected: `False`

- [ ] **Step 2: 创建依赖服务 compose 文件**

`docker/docker-compose.yml` 至少包含：

```yaml
services:
  postgres:
    image: postgres:16
    env_file:
      - ./env/postgres.env
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
  redis:
    image: redis:7
    env_file:
      - ./env/redis.env
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis-data:/data

volumes:
  postgres-data:
  redis-data:
```

- [ ] **Step 3: 创建环境变量文件和中文说明**

`docker/env/postgres.env` 至少包含：

```env
POSTGRES_DB=agent_hub
POSTGRES_USER=agent_hub
POSTGRES_PASSWORD=agent_hub123456
```

`docker/env/redis.env` 可以仅包含注释或最小配置说明。  
`docker/README.md` 用中文说明如何在服务器执行：

```powershell
docker compose up -d
docker compose ps
```

并列出默认端口、账号和数据卷。

- [ ] **Step 4: 更新根 README**

根目录 `README.md` 至少补充：

```md
## 依赖服务

项目根目录提供 `docker/` 目录，用于部署 PostgreSQL 和 Redis。
```

- [ ] **Step 5: 运行绿灯**

Run: `cd C:\Users\Orange\Documents\Agent-Hub\docker; docker compose config`

Expected: PASS，输出规范化后的 compose 配置。

- [ ] **Step 6: 提交 docker 目录**

```powershell
git add docker README.md
git commit -m "chore: add dependency docker deployment"
```

## 任务 7：最终联调、构建验证与展示入口

**Files:**
- Modify: `C:\Users\Orange\Documents\Agent-Hub\docs\superpowers\plans\2026-05-29-agent-platform-initialization.md`

- [ ] **Step 1: 验证后端测试**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:MAVEN_HOME='D:\Program Files\apache-maven-3.9.16'
$env:PATH="$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"
cd C:\Users\Orange\Documents\Agent-Hub\backend
mvn test
```

Expected: PASS，全部 Maven 模块测试通过。

- [ ] **Step 2: 验证管理端构建**

Run:

```powershell
cd C:\Users\Orange\Documents\Agent-Hub\admin-web
npm run type-check
npm run build
```

Expected: PASS，管理端类型检查与构建通过。

- [ ] **Step 3: 启动管理端开发服务**

Run:

```powershell
cd C:\Users\Orange\Documents\Agent-Hub\admin-web
npm run dev -- --host 127.0.0.1 --port 5173
```

Expected: 出现 `http://127.0.0.1:5173` 可访问提示。

- [ ] **Step 4: 启动后端应用**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:MAVEN_HOME='D:\Program Files\apache-maven-3.9.16'
$env:PATH="$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"
cd C:\Users\Orange\Documents\Agent-Hub\backend
mvn -pl agent-hub-server spring-boot:run
```

Expected: 后端启动成功，默认监听本地 HTTP 端口。

- [ ] **Step 5: 手工联调检查**

检查以下结果：

```text
登录成功
顶部、侧边栏、标签页正常显示
当前用户菜单可加载
用户、角色、菜单页面可打开并返回数据
菜单变更后可重新加载
```

- [ ] **Step 6: 提交最终交付**

```powershell
git add .
git commit -m "feat: deliver rbac admin alignment"
```

## 自查结果

规格覆盖：

- `admin-web` 页面与布局对齐：任务 1、任务 2、任务 3 覆盖。
- RBAC 页面范围：任务 3 覆盖。
- 后端 RBAC 与动态菜单：任务 4、任务 5 覆盖。
- Docker 依赖服务目录：任务 6 覆盖。
- 统一响应、全局异常、JWT：任务 4、任务 5 依托现有结构补强。
- 最终联调与可交付验证：任务 7 覆盖。

占位符检查：

- 本计划未使用 `TODO`、`TBD`、`稍后实现` 作为执行步骤。
- 唯一延期内容是 SSE 事件协议，其已在规格中明确排除，不是计划缺项。

类型一致性：

- 前端菜单类型固定为 `MenuItem`。
- 前端认证结果固定为 `LoginResult`。
- 后端用户、角色、菜单命令对象固定为 `UserCommand`、`RoleCommand`、`MenuCommand`。
- 后端服务接口固定为 `UserService`、`RoleService`、`MenuService`。
