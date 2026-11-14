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
- Compose 项目名 `school-panorama`，命名卷 `school-panorama_dbdata`。
