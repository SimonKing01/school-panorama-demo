# 项目级开发规则：学校业务对象全景演示项目

## 目录约束

- 本项目为独立目录 `school-panorama-demo`，不得修改同级或其他项目的文件。
- `db/init/*.sql`：唯一的数据库结构与演示数据来源，只在数据卷为空时执行，脚本必须幂等。
- `backend/`：Java 21 + Spring Boot 3 + MyBatis Plus 后端，唯一写者为后端实现者。
- `frontend/`：Vue 3 + TypeScript + Vite + Element Plus 前端，唯一写者为前端实现者。
- 根目录 `docker-compose.yml`、`*/Dockerfile`、`frontend/nginx.conf`、`docs/`、`tests/`、`README.md` 由主 Agent 维护。

## 接口与数据契约

- `docs/API-CONTRACT.md` 是前后端唯一契约。修改契约需主 Agent 确认，并同步前后端。
- 所有业务视角（总览、查询、列表、详情、课表）必须复用同一份 `lesson_sessions` 数据与同一套共享业务查询，禁止各自复制业务事实。
- 学生按 `students.class_id = lesson_sessions.class_id` 继承班级课表，不建立学生课程关联表。

## 事务与一致性

- 调课必须在单个数据库事务内完成「更新一次授课安排 + 写入一条变更记录」。
- 校验失败时不得产生任何写入（包括变更记录），返回具体冲突原因。
- 任何成功调课或演示时间调整都要递增 `system_revision.revision`，供前端短轮询感知。

## 代码约定

- 简体中文回复；代码注释使用中文，只解释业务意图、边界条件和非显然的设计原因。
- 界面文案全部中文，显著标注「演示数据」「演示时间」。
- 不引入契约之外的框架；不写入真实密钥或生产数据，全部为虚拟数据。
- 修改后必须实际编译/构建并运行验证，禁止仅凭推断宣称通过。

## 端口（避开已有服务）

- 已有服务占用 5432 / 8080 / 5173 / 8083，本项目使用：前端 `5180`、后端 API `18080`、PostgreSQL `55432`。
- 本体平台增量（2026-11-15）：本体平台前端 `5181`、本体服务 API `18090`、本体库 `55433`；新增命名卷 `school-panorama_ontologydata`。
- Compose 项目名 `school-panorama`，业务卷 `school-panorama_dbdata`。

---

## 本体平台增量架构约束（2026-11-15 起生效）

### 三层职责，不可颠倒

| 层 | 目录 | 职责 | 禁止 |
| --- | --- | --- | --- |
| 原业务系统 | `backend/`、`frontend/`、`db/init/` | 业务操作、业务校验、事务；业务库是**唯一业务事实源** | 不得同步依赖本体服务可用 |
| 本体数据底座 | `ontology-service/`、`db/ontology/` | 独立服务 + 独立 PostgreSQL 库；模型定义、映射、投影、同步、对账、历史 | 当前对象/关系只是**可重建的投影**，不得成为第二套业务事实源；不得写业务库 |
| 本体平台 | `ontology-web/` | 独立入口，5 类页面**只调本体 API** | 不得直接改投影冒充业务操作成功；业务操作跳转原业务 |

- 业务处理与 Outbox 事件写入同一个**本地事务**；投递/消费在事务外异步执行。
- 本体服务对业务库只允许**只读**连接（独立池 + `setReadOnly(true)` + 仅 SELECT），用于对账/全量导入/重建；部署时应替换为只读账号。
- 原业务停止时平台仍能查询已同步数据并显示新鲜度；本体服务停止时原业务仍能完成合法修改。

### 目录与契约

- 新增目录：`ontology-service/`（Java 21 + Spring Boot）、`ontology-web/`（Vue 3 + TS + Vite）、`db/ontology/`（本体库 DDL）、`docs/ontology/`（增量 PRD / 架构 / 契约 / 说明）。
- `docs/API-CONTRACT.md` 是**原业务**唯一契约，本体平台改造期间**逐字不改**；`docs/ontology/03-本体API契约.md` 是**本体 API** 唯一契约，统一 `{code,data,message}` 包装、前缀 `/api/ontology`。
- 通用内核与通用前端**禁止**通过增加业务类型 `switch/if` 扩展；新增类型只改模型定义、来源映射与 `adapter/<domain>` 适配模块。
- 学校领域代码集中在 `ontology-service/.../adapter/school/` 与 `resources/ontology/**/*.yaml`。

### 数据与时间铁律

- 对象全局 ID 与来源主键分开：不同数据源中相同主键、相同名称不得自动合并。
- 两类时间严格分离：`demoTime`（演示业务时间，可回拨）与 `occurred_at / delivered_at / processed_at / last_success_sync_at`（真实单调处理时间）。对账/重建发现的历史缺失**不得伪造**操作人、发生时间与中间过程。
- 幂等三件套：事件唯一 ID + 消费去重表 + 条件 UPSERT；旧版本事件/快照一律不得覆盖新状态。
- 重建当前投影**不得**删除变更历史、同步任务、消费进度与错误记录；`platform_revision` 只增不减。

### 迁移与文件所有权

- **业务库 Outbox 表必须由启动迁移器幂等创建**（`CREATE TABLE IF NOT EXISTS`），适用于已有 `school-panorama_dbdata` 数据卷；**严禁依赖删卷重建**升级。
- `docker-compose.yml`、`AGENTS.md`、`README.md`、`.gitignore`、`tests/`、`scripts/` 由主 Agent 维护；其余目录按批次由对应实现者维护，同一文件同一时间只有一个写者。
- 达梦数据库与其他 Java 系统接入**本轮未测试、未实现**，只能在文档中列适配边界与待验证项，不得宣称已支持。
