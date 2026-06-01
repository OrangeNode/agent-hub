# RBAC 内存实现切换数据库设计

## 1. 背景

当前后端 RBAC 能力已经具备基础闭环，但数据实现仍然依赖内存对象：

- `agent-hub-server` 通过 `AppBeans` 直接装配 `InMemoryUserService`、`InMemoryRoleService`、`InMemoryMenuService`、`InMemoryAuthService`
- 用户、角色、菜单和权限关系都保存在 `InMemorySystemStore`
- `JwtService` 当前把 `roles` 和 `permissions` 同时写入 JWT

这套实现适合前期联调，但不满足正式场景的基本要求：

- 数据无法持久化
- 服务重启后 RBAC 状态丢失
- JWT 体积随权限点增长而膨胀
- 角色菜单关系变化后，已签发 token 内的权限无法及时收敛

本次设计目标是把 RBAC 主链路从内存实现切换到 PostgreSQL，保留现有接口协议，补齐数据库、认证和权限装配链路。

## 2. 目标

- 将用户、角色、菜单及其关联关系从内存存储切换到 PostgreSQL
- 持久层使用 `MyBatis-Plus`
- 数据库结构迁移使用 `Flyway`
- 登录密码改为 `BCrypt` 哈希存储与校验
- JWT 只保存最小身份信息：`userId`、`username`、`roles`
- 权限集合不写入 JWT，由服务端查询并装配，可为后续 Redis 缓存预留位置
- 保持现有 `Controller` 的接口协议和统一响应模型不变

## 3. 范围

### 3.1 包含

- `backend` 中 RBAC 持久化能力接入
- 数据库表设计与初始化数据脚本
- `AuthService`、`UserService`、`RoleService`、`MenuService` 的数据库实现
- JWT 载荷收缩
- Spring Security 权限装配链路调整
- BCrypt 密码编码与登录校验

### 3.2 不包含

- `user-web` 改造
- AI、MCP、Skill、Agent 等业务模块权限模型扩展
- 独立 `sys_permission` 表设计
- 数据权限、部门权限、岗位权限
- SSE 事件协议设计

## 4. 总体方案

本次改造采用“标准 RBAC 关系表 + 保留现有服务接口”的策略。

对外边界保持不变：

- `UserService`
- `RoleService`
- `MenuService`
- `AuthService`

对内实现从 `InMemory*Service` 切换为数据库版实现类。这样可以最大限度复用现有：

- `AuthController`
- `SystemController`
- `JwtService`
- `SecurityConfig`
- 前端已接入的 RBAC 接口协议

数据库层采用标准多对多关系表达：

- 用户与角色：多对多
- 角色与菜单：多对多
- 权限点继续挂在 `sys_menu.permission`

这与当前系统按“角色 -> 菜单 -> permission”聚合权限的逻辑一致，可以在不扩大领域建模范围的前提下完成落库。

## 5. 数据模型设计

### 5.1 核心表

#### `sys_user`

- `id` bigint 主键
- `username` varchar 唯一，非空
- `display_name` varchar 非空
- `password` varchar 非空，存储 `BCrypt` 哈希
- `enabled` boolean 非空
- `created_by` bigint 可空
- `created_at` timestamp 非空
- `updated_by` bigint 可空
- `updated_at` timestamp 非空

#### `sys_role`

- `id` bigint 主键
- `code` varchar 唯一，非空
- `name` varchar 非空
- `enabled` boolean 非空
- `created_by` bigint 可空
- `created_at` timestamp 非空
- `updated_by` bigint 可空
- `updated_at` timestamp 非空

#### `sys_menu`

- `id` bigint 主键
- `parent_id` bigint 非空，根节点固定为 `0`
- `name` varchar 非空
- `title` varchar 非空
- `path` varchar 可空
- `component` varchar 可空
- `icon` varchar 可空
- `permission` varchar 可空
- `menu_type` varchar 非空
- `visible` boolean 非空
- `sort` integer 非空
- `created_by` bigint 可空
- `created_at` timestamp 非空
- `updated_by` bigint 可空
- `updated_at` timestamp 非空

#### `sys_user_role`

- `user_id` bigint 非空
- `role_id` bigint 非空
- 联合唯一索引：`(user_id, role_id)`

#### `sys_role_menu`

- `role_id` bigint 非空
- `menu_id` bigint 非空
- 联合唯一索引：`(role_id, menu_id)`

### 5.2 约束原则

- `username`、`role.code` 保证唯一
- `sys_user_role`、`sys_role_menu` 使用关系表，不做 JSON 或数组字段存储
- `permission` 允许为空，因为目录型菜单不一定对应权限点
- 菜单树仍由 `parent_id` 表示层级关系
- 审计字段本次一并落表，避免后续再做结构迁移

## 6. 持久层与模块结构

### 6.1 技术选型

- ORM/持久层：`MyBatis-Plus`
- 迁移工具：`Flyway`
- 数据库：`PostgreSQL`

选型原则如下：

- 简单 CRUD、条件查询交给 `MyBatis-Plus`
- RBAC 关联聚合查询保持显式 SQL 控制
- 不将复杂菜单树、权限聚合完全交给自动映射

### 6.2 代码落点

建议在 `agent-hub-system` 内新增以下结构：

- `com.agenthub.system.user.persistence`
  - `SysUser`
  - `SysUserRole`
  - `UserMapper`
- `com.agenthub.system.role.persistence`
  - `SysRole`
  - `SysRoleMenu`
  - `RoleMapper`
- `com.agenthub.system.menu.persistence`
  - `SysMenu`
  - `MenuMapper`

数据库版服务实现：

- `com.agenthub.system.user.DbUserService`
- `com.agenthub.system.role.DbRoleService`
- `com.agenthub.system.menu.DbMenuService`
- `com.agenthub.system.auth.DbAuthService`

### 6.3 职责边界

#### `DbUserService`

- 用户 CRUD
- 查询用户账号
- 维护用户与角色关系
- 组装 `UserAccount`、`UserProfile`

#### `DbRoleService`

- 角色 CRUD
- 维护角色与菜单关系
- 按角色编码查询角色

#### `DbMenuService`

- 菜单 CRUD
- 菜单树构建
- 根据角色集合计算当前用户可见菜单树

#### `DbAuthService`

- 登录校验
- 当前用户信息查询
- 权限点聚合
- 为安全链路提供角色与权限装配数据

## 7. 认证与授权设计

### 7.1 登录

登录流程调整为：

1. 根据 `username` 查询 `sys_user`
2. 校验用户是否存在
3. 使用 `PasswordEncoder.matches(rawPassword, encodedPassword)` 进行 BCrypt 校验
4. 校验用户启停状态
5. 查询用户角色编码列表
6. 生成只包含 `userId`、`username`、`roles` 的 JWT

### 7.2 JWT 载荷

JWT 本次只保留：

- `userId`
- `username`
- `roles`

不再写入：

- `permissions`

原因如下：

- 权限点数量通常显著多于角色，写入 token 会导致头部膨胀
- 角色菜单关系变化后，旧 token 内的权限快照无法及时失效
- 后续权限模型扩展时，服务端计算权限更利于演进

### 7.3 Spring Security 权限装配

请求链路调整为：

1. `JwtAuthenticationFilter` 解析 token
2. 从 token 中拿到 `userId`、`username`、`roles`
3. 服务端根据 `userId` 重新加载权限集合
4. 将 `ROLE_xxx` 和 permission 一并组装为 `GrantedAuthority`
5. 写入 `SecurityContextHolder`

这样 JWT 与权限快照解耦，权限变更可以在服务端生效。

## 8. 权限与菜单聚合规则

当前系统继续沿用“角色绑定菜单，菜单携带权限点”的规则：

1. 用户绑定多个角色
2. 角色绑定多个菜单
3. 菜单上的 `permission` 字段作为权限标识
4. 当前用户权限集合为其全部启用角色所绑定菜单权限点的去重并集

菜单树查询规则如下：

- 仅统计启用角色
- 仅返回角色可见菜单
- 根据 `parent_id` 在服务层组装树
- 菜单返回顺序按 `sort`、`id` 排序

## 9. 初始化数据策略

当前 `InMemorySystemStore` 中已有默认：

- 管理员用户
- 普通用户
- 管理员角色
- 普通用户角色
- 基础菜单

本次不再保留运行期内存种子数据，而是迁移为 `Flyway` 初始化脚本。

初始化脚本至少包含：

- 角色数据
- 菜单数据
- 用户数据
- 用户角色关系
- 角色菜单关系

管理员密码直接写入 BCrypt 哈希，避免任何明文密码初始化逻辑残留在服务代码中。

## 10. Bean 装配与配置

`agent-hub-server` 侧需要完成以下调整：

- 引入 `MyBatis-Plus` 自动配置
- 引入 `Flyway`
- 配置 Mapper 扫描
- 增加 `PasswordEncoder` Bean，使用 `BCryptPasswordEncoder`
- `AppBeans` 从装配 `InMemory*Service` 切换为数据库实现，或进一步改为组件扫描注入

现有 `application.yml` 中 PostgreSQL 数据源继续沿用。

## 11. 缓存策略预留

本次设计不要求首轮必须完成权限缓存，但权限读取链路需要为 Redis 缓存预留接口位置。

推荐缓存粒度：

- `userId -> permissions`
- 可选：`userId -> currentMenus`

推荐失效时机：

- 用户角色变更
- 角色菜单变更
- 用户禁用
- 用户删除

本次如果不实现缓存，仍应保证 `DbAuthService` 或独立权限读取组件具备未来接入缓存的明确边界。

## 12. 迁移顺序

1. 引入 `MyBatis-Plus` 与 `Flyway` 依赖
2. 编写 `Flyway` 脚本，创建 5 张 RBAC 表并插入初始数据
3. 新增实体、Mapper、数据库版 Service
4. 调整 `JwtService`，收缩 token 字段
5. 调整 `SecurityConfig`，改为服务端加载权限
6. 切换 `AppBeans` 装配到数据库实现
7. 移除运行链路中的 `InMemorySystemStore` 依赖
8. 补齐集成测试

## 13. 测试策略

至少覆盖以下验证点：

- 用户登录成功
- 用户名不存在时登录失败
- BCrypt 密码错误时登录失败
- 用户禁用时登录失败
- 当前用户信息查询正确返回角色集合
- 当前用户权限集合按角色菜单正确聚合
- 当前用户菜单树返回正确结构与排序
- 用户角色变更后，权限聚合结果随之变化
- 角色菜单变更后，权限聚合结果随之变化
- 角色删除、菜单删除后关联关系处理正确

测试层次建议：

- `agent-hub-system`：数据库版服务集成测试
- `agent-hub-server`：控制器与安全链路测试

## 14. 风险与约束

- 保留现有 `UserAccount.roles`、`RoleItem.menuIds` 结构意味着数据库实现中需要做一次关系聚合，这属于有意接受的兼容成本
- JWT 不再携带权限后，请求链路会依赖数据库或缓存读取权限，后续如引入缓存，必须同步设计失效策略
- 本次不拆独立 `sys_permission` 表，以避免改造范围扩散
- 菜单树构建应放在服务层，避免把树形装配逻辑塞进 Mapper

## 15. 交付结果

完成后，系统应具备以下结果：

- RBAC 数据持久化到 PostgreSQL
- 登录密码不再明文存储
- JWT 负载缩小为最小身份信息
- Spring Security 权限装配由服务端动态生成
- 现有管理端用户、角色、菜单接口协议保持稳定
- 为后续 Redis 权限缓存和更细粒度权限模型预留演进空间
