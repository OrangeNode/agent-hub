# Linux 统一启动脚本 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在项目根目录提供 Linux 侧统一启动、停止和状态查看脚本，覆盖 Docker 依赖、后端、两个前端和可选 sandbox-service。

**Architecture:** 新增 `scripts` 目录作为统一入口；共享逻辑放入 `_runtime-common.sh`，启动、停止、状态脚本只编排流程。sandbox-service 继续复用既有脚本，统一入口只负责转调和后端环境衔接。

**Tech Stack:** Bash、Docker Compose、Maven、npm、现有 sandbox-service bash 脚本。

---

### Task 1: 脚本契约验证

**Files:**
- Create: `scripts/verify-runtime-scripts.sh`

- [ ] **Step 1: 写失败验证脚本**

创建 `scripts/verify-runtime-scripts.sh`，检查 `start-all.sh`、`stop-all.sh`、`status-all.sh`、`_runtime-common.sh` 存在，执行 `bash -n`，并检查 `--with-sandbox`、`logs/runtime`、sandbox 既有脚本转调等关键字符串。

- [ ] **Step 2: 验证失败**

Run: `bash scripts/verify-runtime-scripts.sh`

Expected: FAIL，原因是统一启动脚本尚不存在。

### Task 2: 共享运行态工具

**Files:**
- Create: `scripts/_runtime-common.sh`
- Test: `scripts/verify-runtime-scripts.sh`

- [ ] **Step 1: 实现共享函数**

实现项目根目录解析、`logs/runtime`、PID 文件、日志文件、后台启动、进程状态和停止进程函数。

- [ ] **Step 2: 验证语法**

Run: `bash -n scripts/_runtime-common.sh`

Expected: PASS。

### Task 3: 统一启动入口

**Files:**
- Create: `scripts/start-all.sh`
- Test: `scripts/verify-runtime-scripts.sh`

- [ ] **Step 1: 实现启动编排**

实现 Docker 依赖启动、可选 sandbox 转调、后端启动、两个前端启动和 `--help`、`--with-sandbox`、`--skip-deps`、`--no-install` 参数。

- [ ] **Step 2: 验证启动脚本语法**

Run: `bash -n scripts/start-all.sh`

Expected: PASS。

### Task 4: 停止和状态入口

**Files:**
- Create: `scripts/stop-all.sh`
- Create: `scripts/status-all.sh`
- Test: `scripts/verify-runtime-scripts.sh`

- [ ] **Step 1: 实现停止脚本**

按用户端、管理端、后端、sandbox、Docker 依赖顺序停止，支持 `--keep-deps` 和 `--with-sandbox`。

- [ ] **Step 2: 实现状态脚本**

展示本地服务 PID、日志路径、sandbox PID 和 Docker Compose 状态。

- [ ] **Step 3: 验证脚本语法**

Run: `bash -n scripts/stop-all.sh scripts/status-all.sh`

Expected: PASS。

### Task 5: README 入口说明

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 增加统一脚本说明**

在快速启动章节补充 Linux 统一脚本用法，说明 sandbox 需要 `--with-sandbox` 和临时 sudo 授权，不把密码写入仓库。

- [ ] **Step 2: 验证文档包含入口命令**

Run: `rg "scripts/start-all.sh|scripts/stop-all.sh|scripts/status-all.sh" README.md`

Expected: 输出三类脚本命令。

### Task 6: 总体验证

**Files:**
- Test: `scripts/verify-runtime-scripts.sh`

- [ ] **Step 1: 运行验证脚本**

Run: `bash scripts/verify-runtime-scripts.sh`

Expected: PASS。

- [ ] **Step 2: 查看未启动状态**

Run: `bash scripts/status-all.sh`

Expected: 命令退出码为 0，未运行的服务显示“未运行”。
