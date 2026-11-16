# 03 本体 API 契约（增量）

> 角色/作者：架构师 高见远（Gao）｜版本 v1.0｜配套文档：[`02-架构设计.md`](./02-架构设计.md)
> 唯一权威需求来源：[`00-原始需求.md`](./00-原始需求.md) → [`01-增量PRD.md`](./01-增量PRD.md)（F4：分开的原业务 API 与本体 API 契约）

---

## 0. 文档定位与铁律

| 项 | 说明 |
| --- | --- |
| 契约分离 | 本文件给出**两套** API：①「原业务 API」（保持不变 + 本轮新增 1 个批量接口）；②「本体 API」（新增独立服务）。 |
| **原契约逐字不改** | `docs/API-CONTRACT.md` 是原业务前后端唯一契约，**本轮不修改、不污染**。原业务所有既有端点的路径、请求、响应、错误结构保持不变；为接入底座新增的 Outbox 写入对前端透明。 |
| 新增接口归属 | `POST /api/schedule/batch-adjust` 是**本轮新增、非原有**接口（主理人 Q1 决策），其契约写入本文件，**不写进** `docs/API-CONTRACT.md`。 |
| 两套风格禁止混用 | 原业务 API 用平铺风格（带 `demoTime`）；本体 API 用 `{code,data,message}` 包装。详见 §1.1、§2.2。 |
| 交付分工 | 本文件由架构师产出；`docker-compose.yml` / `AGENTS.md` 的端口与服务注册由主理人汇编（见 02 文档 §9.4）。 |

### 0.1 服务地址与前缀

| 系统 | 宿主端口 | 容器内端口 | 前缀 | 访问方式 |
| --- | --- | --- | --- | --- |
| 原业务服务 `backend` | `18080` | `8080` | `/api` | 原前端 `:5180` 经 nginx 反代 `/api` |
| 本体服务 `ontology-service` | `18090` | `8080` | `/api/ontology`（对外）、`/internal`（投递器内部） | 本体平台 `:5181` 经 nginx 反代 `/ontology-api` |
| 本体平台 `ontology-web` | `5181` | `80` | 页面 | 浏览器入口（独立） |

> 端口为 02 文档 §0.3 采用值（已探测空闲），最终以主理人 Compose 汇编为准。

---

## 1. 原业务 API（保持不变 + 1 个新增）

### 1.1 原业务 API 通用约定（沿用 `docs/API-CONTRACT.md`，逐字口径）

- 后端监听 `8080`，所有接口前缀 `/api`；`application/json;charset=UTF-8`；匿名演示接口无鉴权。
- 日期 `yyyy-MM-dd`、时间 `HH:mm`、时间戳 `yyyy-MM-dd'T'HH:mm:ss`（无时区，按演示本地时间解释）。
- 除 `/api/health` 外，响应都带 `demoTime`（当前统一演示时间戳）。
- 错误统一 `{"code":"...","message":"...","conflicts":[...],"demoTime":"..."}`，HTTP 400（校验失败）/404（不存在）/500（服务异常）。
- **本轮内部增量（对前端透明）**：`adjust`/`batch-adjust`/`clock`/`sync-today`/`reset` 在**原业务本地单事务**内追加 `outbox_events` 写入；`preview` 不产生事件。所有既有端点返回结构不变。

### 1.2 既有端点清单（保持不变，仅列口径，详见原契约）

| 分组 | 方法 & 路径 | 响应要点（不变） |
| --- | --- | --- |
| 健康 | `GET /api/health` | `{status,db,demoTime,revision}` |
| 元数据 | `GET /api/meta/options` | `{demoTime,schools,classes,teachers,courses,classrooms,subjects}` |
| 演示时钟 | `GET /api/demo/clock` | `{demoTime,running,lessonDate,lessonEndTime,presets}` |
| 演示时钟 | `POST /api/demo/clock` | body `{demoTime?|minuteOffset?|running?}`；返回同 GET，递增 revision |
| 演示时钟 | `POST /api/demo/clock/sync-today` | 整体平移到今天（单事务），返回同 GET，递增 revision |
| 恢复 | `POST /api/demo/reset` | 恢复初始快照，返回同 GET，递增 revision |
| 总览 | `GET /api/overview` | `{demoTime,counts,perSchool,currentLessons,todayLessons,recentChanges}` |
| 查询 | `GET /api/search?q=&type=&schoolId=&classId=` | `{demoTime,total,items:[ObjectRow]}`（`currentLesson/nextLesson` 必须有值） |
| 列表 | `GET /api/objects?type=&schoolId=&classId=&q=` | `{demoTime,type,total,items:[ObjectRow]}` |
| 详情 | `GET /api/objects/{type}/{id}` | `{demoTime,type,id,code,name,school,attributes,relations,currentLesson,nextLesson,schedule,recentChanges,studentCount}` |
| 课表 | `GET /api/schedule?schoolId=&classId=&teacherId=&date=&status=` | `{demoTime,total,items:[LessonView],stats}` |
| 课表详情 | `GET /api/schedule/{id}` | `{demoTime,lesson,before,candidates}` |
| 预览 | `POST /api/schedule/{id}/preview` | **不写入**：`{demoTime,valid,conflicts,before,after,diff,impact}`；**不产生 Outbox 事件** |
| 调课 | `POST /api/schedule/{id}/adjust` | `{demoTime,lesson,change,impact,diff,revision}`；单事务（更新+change_records+revision+Outbox） |
| 变更 | `GET /api/changes?schoolId=&limit=` | `{demoTime,total,items:[ChangeItem]}` |
| 变更详情 | `GET /api/changes/{id}` | `ChangeItem`（含 impact、diff、可跳转对象链接） |
| 短轮询 | `GET /api/revision` | `{revision,demoTime,running}` |

> 校验规则（8 条，顺序固定）见 `docs/API-CONTRACT.md` §8：`REASON_REQUIRED → TIME_INVALID → TEACHER_SCHOOL_MISMATCH → TEACHER_SUBJECT_MISMATCH → CLASSROOM_SCHOOL_MISMATCH → TEACHER_CONFLICT → CLASS_CONFLICT → CLASSROOM_CONFLICT`。**批量接口复用同一套校验，不新增规则**。

### 1.3 【本轮新增，非原有】批量调课 `POST /api/schedule/batch-adjust`

> **标注**：原系统**没有**独立批量调整接口（读码事实，见 PRD §2.5 / Q1）。本接口为主理人拍板本轮新增，用于满足「覆盖批量调整」语义，并产生**批次 Outbox 事件**。它**不是** `sync-today`/`reset`（那是演示数据整体平移/复位）。

**请求**

```jsonc
POST /api/schedule/batch-adjust
Content-Type: application/json;charset=UTF-8
{
  "reason": "批量教研调课（统一兜底原因，可选）",
  "items": [
    { "sessionId": 3, "teacherId": 5, "classroomId": 6,
      "lessonDate": "2026-11-14", "startTime": "15:30", "endTime": "16:15",
      "reason": "教师参加教研，调至第七节并更换教室" },
    { "sessionId": 9, "startTime": "15:30", "endTime": "16:15" }
  ]
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `reason` | string | 否 | 统一兜底原因；某 `item` 未带 `reason` 时用此值；若二者皆空 → `REASON_REQUIRED` |
| `items` | array | 是 | 至少 1 条，最多建议 50 条；空数组 → HTTP 400 |
| `items[].sessionId` | long | 是 | 授课安排 id（对应 `lesson_sessions.id`） |
| `items[].teacherId` / `classroomId` | long | 否 | 不传则沿用原值 |
| `items[].lessonDate` / `startTime` / `endTime` | string | 否 | 不传则沿用原值；格式同原契约 |
| `items[].reason` | string | 否 | 本条原因，优先于统一 `reason` |

**成功响应（HTTP 200）**

```jsonc
{
  "demoTime": "2026-11-14T10:15:00",
  "revision": 86,                       // 整体只 bump 一次
  "batchId": "BATCH-20261114-0001",     // 批次 ID，写入 Outbox 事件 batch_id
  "appliedCount": 2,
  "lessons": [ /* LessonView，逐条 */ ],
  "changes": [ /* ChangeItem，逐条 */ ],
  "impact": { /* 汇总 impact：teacherIds/classroomIds/classIds/studentIds 去重 */ }
}
```

**失败响应（HTTP 400）**：与 `preview` 同结构，附 `message`/`code`，**整体回滚、零写入、零 Outbox 事件**。

```jsonc
{
  "code": "VALIDATION_FAILED",
  "message": "批量调课校验未通过",
  "conflicts": [
    { "type": "TEACHER_CONFLICT", "message": "第 2 条：教师吴嘉豪 10:00-10:45 已在初一（2）班授课" }
  ],
  "demoTime": "...",
  "valid": false,
  "items": [ /* 各条 preview 结构：before/after/diff/impact，便于前端定位 */ ]
}
```

**事务与一致性（硬性，02 文档 §6.1）**

1. 事务开始 `SELECT pg_advisory_xact_lock(918273)` 串行化（与单条 `adjust` 同锁）。
2. 对 `items` **逐条复用 `AdjustService` 的 8 条校验**；校验基于本事务内已 `apply` 的改动可见，避免批次内部互相冲突。
3. **任一 `item` 有 `conflicts` → 抛 `ApiException(400)` 整体回滚**：不产生任何 `lesson_sessions`/`change_records` 写入，revision 不变，不写 Outbox。
4. 全部通过 → 逐条 `applyAdjust` + `insertChange`（`occurred_at` 用 demoTime）→ `bumpRevision()` **一次** → 写 **1 条** `BATCH_ADJUST` Outbox 事件（`payload.items[]` 含全部 before/after/diff/impact，共用 `batch_id`）→ 提交。
5. `preview`（单条）行为不变，仍不产生事件；批量接口不提供 preview。

**Outbox 覆盖基线（Q1）**：`adjust` + `batch-adjust`（新增）+ `clock` + `sync-today` + `reset` 共 **5 个写入口**；`preview` 不产生事件。

---

## 2. 本体 API（新增独立服务）

### 2.1 约定

| 项 | 口径 |
| --- | --- |
| 前缀 | `/api/ontology`（对外）；`/internal`（投递器内部，不暴露给浏览器） |
| 响应包装 | 统一 `{"code":"OK"|错误码, "data": ..., "message":"..."}`；`data` 为各端点负载，可为对象/数组/null |
| HTTP 状态 | 200 成功（`code=OK`）；400 参数/校验失败；404 对象/资源不存在；409 任务冲突（如已有导入在跑）；500 服务异常 |
| 时间格式 | 业务时间戳 `yyyy-MM-dd'T'HH:mm:ss`（无时区，演示本地时间）；处理时间戳同格式但语义为真实时钟，字段名带 `At`/`Time` 区分（见下） |
| 两类时间分离 | `demoTime`（演示业务时间，可回拨）/ `businessTime`（事件发生时的演示时间）与 `processedAt` / `lastSuccessSyncAt` / `deliveredAt`（真实单调处理时间）**禁止混用** |
| 分页 | `?page=1&size=20`，响应 `data` 内 `{total,page,size,items:[]}` |
| 筛选 | `?filters=<JSON>` 或按类型 `filterableFields` 平铺（如 `schoolId=1&classId=1`）；查询上下文由前端 `router query` 保留 |
| 动态计算 | 所有含动态结果的端点接受可选 `?queryTime=2026-11-14T10:15:00`；不传则取底座 `ont_data_source.business_time`（demoTime）实时计算 |
| 全局 ID | 路径中的 `{globalId}` 为 `gid:{sourceId}:{objectType}:{sourcePk}`，URL 需 encode；不按名称定位 |
| 只读 | 除 `/import/*`、`/reconcile`、`/rebuild`、`/admin/retry`、`/internal/events` 为运维写入外，其余端点**只读**；平台不写投影冒充业务操作 |

### 2.2 统一错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| `OK` | 200 | 成功 |
| `BAD_REQUEST` | 400 | 参数缺失/非法（如 `depth>2`、`limit>200`、非法 queryTime） |
| `NOT_FOUND` | 404 | 对象/关系/任务不存在 |
| `TASK_CONFLICT` | 409 | 已有导入/对账/重建任务运行中 |
| `SOURCE_UNAVAILABLE` | 200（带标记） | 来源业务库不可达（**不报错**，返回已同步数据 + `sourceUp=false`） |
| `INTERNAL_ERROR` | 500 | 服务异常（写 `ont_error_log`） |

> `SOURCE_UNAVAILABLE` 作为 `data.sourceUp=false` 与 `message` 提示返回，**不阻断**已同步数据查询（验收二 B）。

### 2.3 模型与接入管理

#### `GET /api/ontology/model/types`
驱动前端类型列表/列/详情字段/可搜索项（B1、E2）。

```jsonc
{
  "code": "OK",
  "data": {
    "mappingVersion": "school-panorama:9f3a1c2d7e5b",
    "types": [
      {
        "id": "student", "displayName": "学生", "description": "...",
        "titleTemplate": "{name}",
        "listFields": [
          { "name":"code", "displayName":"学号编号", "type":"string" },
          { "name":"name", "displayName":"姓名",   "type":"string" },
          { "name":"gender","displayName":"性别",   "type":"string" },
          { "name":"className","displayName":"所在班级","type":"string","derived":true }
        ],
        "detailFields": [ /* 同结构，含 derivedProperties 与 dynamicResolvers 标记 */ ],
        "searchableFields": ["code","name","studentNo"],
        "filterableFields": ["schoolId","classId","gender"],
        "derivedProperties": [ { "name":"className","provider":"classLookupProvider" } ],
        "dynamicResolvers": ["currentLesson","nextLesson"],
        "relations": [
          { "id":"student_in_class","forwardName":"所在班级","reverseName":"本班学生","toType":"class","cardinality":"N:1" }
        ],
        "sourceMapping": { "sourceId":"school-panorama","sourceTable":"students","sourcePk":"id" }
      }
      // ... school / class / teacher / course / lessonSession /(批次④后) classroom
    ]
  },
  "message": ""
}
```

#### `GET /api/ontology/model/relations`
```jsonc
{ "code":"OK", "data": { "mappingVersion":"...", "relations":[
  { "id":"session_taught_by","displayName":"授课安排-授课教师",
    "fromType":"lessonSession","toType":"teacher",
    "forwardName":"授课教师","reverseName":"授课安排","direction":"OUT",
    "cardinality":"N:1","businessEffectiveTime":true,
    "generation":{ "kind":"FOREIGN_KEY","sourceTable":"lesson_sessions","fkColumn":"teacher_id" },
    "sourceMappingRef":"school-source.yaml#lessonSession" }
]}}
```

#### `GET /api/ontology/model/mappings`
```jsonc
{ "code":"OK", "data": {
  "dataSources":[
    { "id":"school-panorama","displayName":"学校全景演示业务库","type":"JDBC_POSTGRESQL",
      "watermarkKind":"SYSTEM_REVISION","status":"ACTIVE","sourceUp":true }
  ],
  "objectMappings":[ { "objectType":"student","sourceTable":"students","fieldMappings":[...],
                       "relationRules":[...],"stableIdRule":"gid:{sourceId}:{objectType}:{sourcePk}" } ],
  "versions":[ { "sourceId":"school-panorama","mappingVersion":"...","definitionHash":"sha256:...","activatedAt":"2026-11-14T11:00:00","status":"ACTIVE" } ],
  "providers":[ {"key":"classStudentCountProvider","impl":"...adapter.school.provider.ClassStudentCountProvider"} ],
  "resolvers":[ {"key":"currentLesson","impl":"...adapter.school.resolver.SchoolLessonResolver"} ]
}}
```

### 2.4 全景总览

#### `GET /api/ontology/overview`
```jsonc
{ "code":"OK", "data": {
  "counts": { "objects":90, "relations":210, "objectTypes":6, "relationTypes":9 },
  "dataSource": { "sourceUp":true, "status":"SYNCED", "lastSuccessSyncAt":"2026-11-14T11:05:12","pendingCount":0 },
  "sync": { "revision":86, "demoTime":"2026-11-14T10:15:00", "errorCount":0, "freshness":"已同步" },
  "recentChanges":[ { "changeId":12,"globalId":"gid:...:lessonSession:3","title":"LS-003 语文 初一（1）班",
                      "changeKind":"ATTR_CHANGE","businessTime":"2026-11-14T10:15:00","processedAt":"2026-11-14T11:05:12",
                      "summary":"授课教师 张明远 → 陈思琪" } ],
  "impactSummary": { "selfChanged":3, "derivedRelated":8 }
}}
```

### 2.5 全局搜索

#### `GET /api/ontology/search?q=`
按各类型 `searchableFields` 跨**所有已接入类型**（含授课安排、批次④后的教室）命中，直达对象（US-01、Q4）。

```jsonc
{ "code":"OK", "data": { "q":"陈", "total":3,
  "items":[
    { "globalId":"gid:school-panorama:student:1","type":"student","title":"陈一诺",
      "matchedField":"name","highlight":"<em>陈</em>一诺","attributes":{ "code":"STU-0001","className":"初一（1）班" },
      "sourceSummary":{ "sourceId":"school-panorama","sourceTable":"students","sourcePk":"1","mappingVersion":"..." } }
  ]}}
```

### 2.6 通用对象探索器

#### `GET /api/ontology/objects?type=&q=&filters=&page=&size=&queryTime=`
```jsonc
{ "code":"OK", "data": {
  "type":"student","page":1,"size":20,"total":32,
  "items":[
    { "globalId":"gid:school-panorama:student:1","title":"陈一诺",
      "attributes":{ "code":"STU-0001","name":"陈一诺","gender":"女","className":"初一（1）班" },
      "dynamicResults":[
        { "key":"currentLesson","value":{ "code":"LS-003","courseName":"语文","startTime":"10:00","endTime":"10:45" },
          "basis":"依据演示时间 2026-11-14 10:15，按所在班级 CLS-01 的授课安排，取 开始≤10:15<结束 的最早一节",
          "inputs":["gid:...:class:1","gid:...:lessonSession:3"],"queryTime":"2026-11-14T10:15:00" },
        { "key":"nextLesson","value":{...},"basis":"取 开始>10:15 中时间最早的一节", ... }
      ],
      "sourceSummary":{ "sourceId":"school-panorama","sourceTable":"students","sourcePk":"1","sourceCode":"STU-0001","mappingVersion":"..." }
    }
  ]}}
```

#### `GET /api/ontology/objects/{globalId}?queryTime=`
```jsonc
{ "code":"OK", "data": {
  "globalId":"gid:school-panorama:teacher:5","type":"teacher","title":"陈思琪",
  "mappingVersion":"school-panorama:9f3a1c2d7e5b",
  "attributes":[ { "name":"code","displayName":"编号","value":"TCH-005","origin":"SOURCE" },
                 { "name":"teachingClassNames","displayName":"任教班级(课表推断)","value":["初一（1）班"],"origin":"DERIVED","provider":"teacherClassProvider" } ],
  "dynamicResults":[ { "key":"currentLesson","value":{...} | null,"basis":"...","inputs":[...],"queryTime":"..." } ],
  "relations":{
    "forward":[ { "relationId":"...","relationType":"teacher_in_school","displayName":"所属学校",
                  "target":{ "globalId":"gid:...:school:1","title":"星海第一中学" },
                  "reason":"外键 teachers.school_id → schools.id","sourceTable":"teachers","sourcePk":"5",
                  "mappingVersion":"...","validFrom":null,"validTo":null,"active":true } ],
    "reverse":[ { "relationType":"session_taught_by","displayName":"授课安排",
                  "source":{ "globalId":"gid:...:lessonSession:3","title":"LS-003 语文" },
                  "reason":"外键 lesson_sessions.teacher_id","active":true } ]
  },
  "recentChanges":[ /* 最近变化，含 changeId 可跳转 */ ],
  "source":{ "sourceId":"school-panorama","sourceTable":"teachers","sourcePk":"5","sourceCode":"TCH-005","sourceHash":"...","mappingVersion":"..." },
  "backToBusinessUrl":"http://<host>:5180/objects?type=teacher"   // 返回原业务对象入口（A5）
}}
```

> `attributes[].origin` 区分 `SOURCE`（来源直接存在）/ `DERIVED`（映射或计算得到）；`dynamicResults` 是依赖 `queryTime` 的动态结果（C2 三类可辨识）。

### 2.7 关系探索

#### `GET /api/ontology/objects/{globalId}/relations?direction=forward|reverse|both&depth=1&page=&size=`
```jsonc
{ "code":"OK", "data": { "globalId":"...","depth":1,"page":1,"size":20,"total":12,
  "items":[
    { "relationId":"...","relationType":"session_taught_by","displayName":"授课教师",
      "from":{ "globalId":"gid:...:lessonSession:3","title":"LS-003" },
      "to":{ "globalId":"gid:...:teacher:5","title":"陈思琪" },
      "reason":"外键 lesson_sessions.teacher_id → teachers.id",
      "generationKind":"FOREIGN_KEY",
      "source":{ "sourceTable":"lesson_sessions","sourcePk":"3","sourceCode":"LS-003","sourceHash":"..." },
      "mappingVersion":"...","validFrom":"2026-11-14T15:30:00","validTo":null,"active":true,
      "appliedRevision":85 }
    // 旧关系示例：active=false, validTo 有值, reason 相同 → 「旧关系结束 / 新关系建立」
  ]}}
```

> 边集合语义（`/relations` 与 `/graph` 一致）：`depth=1` 只返回**中心对象自己**的边（`direction=forward` 取 `from=中心`，`reverse` 取 `to=中心`，`both` 取并集）；`depth=2` 返回「中心 ∪ 一跳邻居」的边。结果按 `relationId` 去重，`total` 为去重后的真实条数；`depth=2` 时中心自身的边排在最前。

#### `GET /api/ontology/objects/{globalId}/graph?depth=1|2&limit=50&page=`
关系图数据（Q5：1/2 层 + 节点上限，默认 50，超限分页）。

```jsonc
{ "code":"OK", "data": {
  "center":"gid:...:lessonSession:3","depth":2,"limit":50,"page":1,"truncated":false,
  "nodes":[ { "globalId":"gid:...:lessonSession:3","type":"lessonSession","title":"LS-003 语文","layer":0 },
            { "globalId":"gid:...:teacher:1","type":"teacher","title":"张明远","layer":1 },
            { "globalId":"gid:...:teacher:5","type":"teacher","title":"陈思琪","layer":1 } ],
  "edges":[ { "relationId":"...","relationType":"session_taught_by","from":"gid:...:lessonSession:3",
              "to":"gid:...:teacher:5","active":true,"label":"授课教师",
              "reason":"外键 lesson_sessions.teacher_id","basis":"新关系建立（revision 85）" },
            { "relationId":"...","to":"gid:...:teacher:1","active":false,"label":"授课教师(已结束)",
              "reason":"外键 lesson_sessions.teacher_id","basis":"旧关系于 revision 85 结束" } ]
}}
```

> `depth` 仅允许 1 或 2；`limit` 上限 200，默认 50；超出返回 `truncated=true` + 分页。点边用 `reason`/`basis` 解释成因（E3）。

### 2.8 变化与影响

#### `GET /api/ontology/changes?globalId=&role=&page=&size=`
`role=SELF_CHANGED`（本身发生变化）/ `DERIVED_RELATED`（按规则计算的相关对象）。**双侧可查**：旧老师、新老师传各自 `globalId` 均能查到同一 `changeId`（US-04、E4）。

```jsonc
{ "code":"OK", "data": { "page":1,"size":20,"total":1,"items":[
  { "changeId":12,"eventId":"9f1c...","origin":"EVENT",
    "selfObject":{ "globalId":"gid:...:lessonSession:3","title":"LS-003" },
    "changeKind":"ATTR_CHANGE+REL_CHANGE",
    "diff":[ { "field":"teacherName","label":"授课教师","before":"张明远","after":"陈思琪" } ],
    "relationsAdded":[ { "relationType":"session_taught_by","to":"gid:...:teacher:5","toTitle":"陈思琪","reason":"外键 teacher_id" } ],
    "relationsRemoved":[ { "relationType":"session_taught_by","to":"gid:...:teacher:1","toTitle":"张明远","reason":"旧关系结束" } ],
    "impact":[ { "globalId":"gid:...:teacher:1","role":"SELF_CHANGED","basis":"作为旧关系端点，本次关系被解除" },
               { "globalId":"gid:...:teacher:5","role":"SELF_CHANGED","basis":"作为新关系端点，本次关系建立" },
               { "globalId":"gid:...:student:1","role":"DERIVED_RELATED","basis":"仅为所在班级课表的邻接对象，业务状态未改变" } ],
    "source":{ "eventId":"9f1c...","sourceTable":"lesson_sessions","sourcePk":"3","revision":85 },
    "reason":"教师参加教研","operator":"演示管理员",
    "businessTime":"2026-11-14T10:15:00",        // 演示业务时间（仅 EVENT）
    "processedAt":"2026-11-14T11:05:12",          // 真实处理时间
    "mappingVersion":"..." }
]}}
```

#### `GET /api/ontology/changes/{changeId}` → 单条详情（结构同上 + 完整前后快照 `beforeJson/afterJson`）。

> **对账修复的历史**（D7）：`origin="RECONCILE"` 时 `operator=null`、`businessTime=null`、`reason="对账修复（非业务操作）"`，不伪造操作人/发生时间/中间过程；`origin="REBUILD"` 类似。

### 2.9 数据源状态与新鲜度

#### `GET /api/ontology/sources/status`
```jsonc
{ "code":"OK", "data": {
  "sourceId":"school-panorama","sourceUp":true,
  "status":"SYNCED",                       // 五态：SYNCED / NO_DATA / NOT_CONNECTED / LAGGING / FAILED
  "statusLabel":"已同步（新鲜）",
  "lastSuccessSyncAt":"2026-11-14T11:05:12",   // 真实处理时间（D8）
  "demoTime":"2026-11-14T10:15:00",            // 演示业务时间（D8）
  "snapshotRevision":86,"currentSourceRevision":86,
  "pendingCount":0,"processingCount":0,"errorCount":0,"deadCount":0,
  "freshness":"已同步",
  "mappingVersion":"school-panorama:9f3a1c2d7e5b",
  "hint":""                                // NO_DATA/NOT_CONNECTED/LAGGING/FAILED 时给中文引导
}}
```

| `status` | 含义（PRD §6.1） | 判定条件 |
| --- | --- | --- |
| `SYNCED` | 已同步（新鲜，基准） | 有 `lastSuccessSyncAt` 且 `pendingCount=0`、无未恢复失败 |
| `NO_DATA` | 没有业务数据 | 已配置且有成功导入记录，但源/对象数为 0 |
| `NOT_CONNECTED` | 尚未接入 | 无成功导入记录、无生效映射版本 |
| `LAGGING` | 同步落后 | `pendingCount>0` 或 `lastSuccessSyncAt` 落后于源最新变更 |
| `FAILED` | 同步失败 | 存在失败事件 / 重试耗尽 / 任务异常 |
| `sourceUp=false` | 来源不可用（叠加标记，非第五态） | 健康检查/连接探测失败；仍返回已同步数据 |

#### `GET /api/ontology/revision`
前端短轮询端点（§4.7）：
```jsonc
{ "code":"OK", "data": { "revision":132, "demoTime":"2026-11-14T10:15:00",
  "lastSuccessSyncAt":"2026-11-14T11:05:12","pendingCount":0,"sourceUp":true } }
```
> `revision` = `ont_platform_revision`（单调递增，**只增不减**，重建也 bump 到新值）；前端「**刷新全部视角成功后才 ack**」该值，失败不 ack → 自动重试。

### 2.10 同步任务 / 全量导入

#### `POST /api/ontology/import/full`
请求 `{}`（无 body）或 `{"sourceId":"school-panorama"}`。
```jsonc
// 200
{ "code":"OK", "data": { "taskId":7,"type":"FULL_IMPORT","status":"RUNNING",
  "snapshotRevision":84,"message":"全量导入已启动" } }
// 409（已有任务运行）
{ "code":"TASK_CONFLICT","data":{ "runningTaskId":6 },"message":"已有导入任务在运行" }
```

#### `GET /api/ontology/import/progress?taskId=7`
```jsonc
{ "code":"OK","data": { "taskId":7,"type":"FULL_IMPORT","status":"RUNNING",  // RUNNING/SUCCESS/FAILED
  "snapshotRevision":84,"endRevision":86,
  "progress": { "total":90,"scanned":60,"applied":60,"percent":67,"currentType":"lessonSession" },
  "startedAt":"2026-11-14T11:00:00","finishedAt":null,
  "error":null,"retryable":true } }
```

#### `POST /api/ontology/import/retry` body `{ "taskId":7 }` → 从断点/水位幂等重跑，返回同 `import/full`（重复导入不产生重复对象与关系，D1）。

#### `GET /api/ontology/sync/tasks?type=&page=&size=`
```jsonc
{ "code":"OK","data": { "total":3,"page":1,"size":20,"items":[
  { "taskId":7,"type":"FULL_IMPORT","status":"SUCCESS","snapshotRevision":84,"endRevision":86,
    "startedAt":"...","finishedAt":"...","operator":"system" },
  { "taskId":8,"type":"REBUILD","status":"SUCCESS","startedAt":"...","finishedAt":"..." },
  { "taskId":9,"type":"RECONCILE","status":"SUCCESS","startedAt":"...","finishedAt":"..." }
]}}
```

### 2.11 对账

#### `POST /api/ontology/reconcile`
请求 `{}` 或 `{"dryRun":false, "sourceId":"school-panorama"}`。
```jsonc
{ "code":"OK","data": { "runId":5,"status":"SUCCESS",
  "summary": { "checkedObjects":90,"checkedRelations":210,
               "missing":1,"orphan":0,"fieldDiff":2,"relationDiff":1,"fixed":4 },
  "diffs":[
    { "kind":"FIELD_DIFF","globalId":"gid:...:student:17","field":"className",
      "sourceValue":"初二（1）班","projectionValue":"初一（1）班","fixed":true,
      "note":"绕过业务服务直改库产生，无对应事件；仅修复当前投影，未伪造历史" },
    { "kind":"MISSING","globalId":"gid:...:lessonSession:25","fixed":true,"note":"..." }
  ],
  "changeHistoryNote":"修复记录 origin=RECONCILE，operator/businessTime 为 null，不伪造操作人与发生时间",
  "startedAt":"...","finishedAt":"..." } }
```

#### `GET /api/ontology/reconcile/runs?page=&size=` → 历史运行记录（`ont_reconcile_run`，可重复执行）。

### 2.12 重建当前投影

#### `POST /api/ontology/rebuild`
请求 `{}` 或 `{"sourceId":"school-panorama"}`。
```jsonc
{ "code":"OK","data": { "taskId":8,"type":"REBUILD","status":"RUNNING",
  "scope":"CURRENT_PROJECTION_ONLY",
  "truncatedTables":["ont_object","ont_relation","ont_source_ref","ont_object_version"],
  "preservedTables":["ont_change_history","ont_change_impact","ont_sync_task","ont_processed_event","ont_error_log","ont_reconcile_run","ont_model_def","ont_mapping_version","ont_data_source","ont_platform_revision"],
  "message":"仅重建当前投影，审计历史全保留（Q2）" } }
```
> 重建前后 `globalId`/当前属性/关系一致；`ont_platform_revision` **只增不减**；审计历史条数不减少（A3、D3、验收五）。

### 2.13 管理 / 失败重试

#### `GET /api/ontology/admin/outbox?status=&page=&size=`
`status`: `PENDING/FAILED/DEAD/DELIVERED`。
```jsonc
{ "code":"OK","data": { "total":1,"items":[
  { "eventId":"9f1c...","batchId":null,"changeKind":"ADJUST","revision":85,
    "status":"DEAD","attempts":8,"lastError":"connection refused",
    "businessTime":"2026-11-14T10:15:00","occurredAt":"2026-11-14T11:00:01","nextRetryAt":null }
]}}
```

#### `POST /api/ontology/admin/retry?eventId=9f1c...`
→ `{ "code":"OK","data":{ "eventId":"...","status":"PENDING","attempts":0,"message":"已重新排队" } }`（供 E5「失败重试入口」与验收三注入重复/乱序事件）。

---

### 2.14 关系类型在线编辑（增量，2026-11-16 实施）

> 详见 [`07-在线编辑关系类型-增量设计.md`](./07-在线编辑关系类型-增量设计.md)。**既有 `GET /model/relations` 结构不变，只新增字段**：
> `generation` 除原有 `kind/sourceTable/fkColumn/targetTable/rule` 外，补齐 `distinct/sourceColumn/joinTable/joinFilterColumn/joinSelectColumn/validFromColumns`（编辑表单需要完整规则，否则「打开一个联表关系再保存」会抹掉联表参数）。

所有写端点请求体必带 `baseMappingVersion`（乐观版本，等于当前生效版本，否则 409 `VERSION_CONFLICT`）；`operator` 缺省「演示管理员」，`note` 缺省按操作自动生成。
保存 ≠ 生效：写 `<definition-dir>/relation-types.yaml` 并登记 `PENDING`，点「应用到运行时」后由本体服务受控重启加载。

| 端点 | 说明 | 主要错误码 |
| --- | --- | --- |
| `POST /model/relations` | 新增一条（保存草稿） | 400 `VALIDATION_FAILED`（逐条中文 `data.errors[]`）、409 `VERSION_CONFLICT` / `TASK_CONFLICT` / `DEFINITION_LOCKED` |
| `PUT /model/relations/{id}` | 修改；**id 不可改名** | 同上 + 404 `NOT_FOUND` |
| `DELETE /model/relations/{id}` | 删除；被映射引用时先要 `force` | 409 `DELETE_IMPACT_CONFIRM`（带 `referencedByMappings/impactedObjectTypes/hint`）；被引用时带 `force=true` 返回 409 `DELETE_BLOCKED_BY_MAPPING`（见下） |
| `POST /model/relations/reset` | 恢复打包基线（删 overlay） | 400 `BAD_REQUEST`（`confirm!=true`） |
| `POST /model/relations/apply` | 应用到运行时 | **202** `{applying:true,...}`（自重启开启）/ 200 `{pendingRestart:true}`（关闭时降级）/ 200 `{noChange:true}` / 409 `APPLY_IN_PROGRESS` / `VERSION_CONFLICT` / `TASK_CONFLICT` |
| `GET /model/versions` | 生效版本 + 草稿 + 上次回退 + 时间线 | — |

**删除被引用关系的边界（与 07 文档 §6.3 的差异，已回报主理人）**：`school-source.yaml` 里的 `relationRules/joinRelationRules` 会反向校验「关系类型必须已定义」，且重建时 `SnapshotBuilder` 会 `registry.relationOf(rule.relationType)`；因此删掉一个**仍被来源映射引用**的关系类型会让生效定义在下次启动校验时整体非法（服务回退基线、改动静默失效）。故本实现：未带 `force` 返回影响清单；带 `force=true` 返回 409 `DELETE_BLOCKED_BY_MAPPING` 并说明「需先在来源映射中移除对应规则」。在线新增（未被映射引用）的关系可正常删除。

**双闸门**：打包基线（classpath 三份 YAML）非法 → `IllegalStateException` 拒绝启动（口径不变）；overlay 非法 → 隔离为 `relation-types.yaml.invalid.<ts>` + 写 `ont_error_log(phase=OVERLAY_FALLBACK)` + `GET /model/versions.lastFallback` 暴露原因 + 回退基线照常启动，**绝不锁死**。

#### 2.14.1 版本与操作人留存（P6 缺陷修复，2026-11-16）

**问题**：保存时传了 `operator/note`，但服务重启激活时被 `system / startup-overlay` 覆盖，`ont_mapping_version` 与 `ont_model_def` 里查不到「这一版是谁改的、长什么样」。

**修法（对契约只做字段新增，不改既有字段名与结构）**：

| 时机 | 落库动作 |
| --- | --- |
| `POST/PUT/DELETE /model/relations*` 成功 | `ont_mapping_version` 写一行 `status=PENDING`，`mapping_version`=草稿哈希，`operator/note` 取请求体（缺省「演示管理员」/自动文案），`definition_source=OVERLAY`。**同一草稿重复保存覆盖同一行**（唯一键 `(source_id, mapping_version)`），不堆 PENDING |
| `POST /model/relations/reset` 成功 | 同上登记一行 PENDING，但 `mapping_version`=打包基线哈希、`definition_source=BASELINE`，`operator/note` 取请求体。若该版本正好是当前 ACTIVE（例如草稿未应用就恢复），保持 `ACTIVE` 不变，只更新署名——避免出现「一行 ACTIVE 都没有」的空窗 |
| 启动激活（`DefinitionActivationRunner` @Order 15） | 若该版本行**已有人写的 operator** → 原地把 `PENDING` 转 `ACTIVE` 并**原样保留 operator/note**；只有该行不存在或 operator 为 `system` 时，才记 `system` + `startup-baseline` / `startup-overlay` / `startup-overlay-fallback` |
| `MappingVersionRecorder` @Order 20 | 幂等补登记：署名同样由版本行解析（不是固定 `system`），对已有人工署名的行只更新状态/哈希，绝不覆盖 operator/note |
| 每次激活 | `ont_model_def` 除既有的 `object-types` / `relation-types` / `source-mapping` 三行「当前生效内容」外，另按版本留一行 `def_key = <type>@<mapping_version>`（`ON CONFLICT DO NOTHING`），使「按版本号回看那一版长什么样」成立；既有 `def_key` 行保持不变，读取方无感 |

`GET /model/versions` 新增字段（只增不改）：
- 顶层 `activeOperator` / `activeNote`：当前生效版本是谁签字生效的；
- `draft.savedBy` / `draft.savedNote`：谁在什么时候（`draft.savedAt`）保存的这份待生效草稿；
- `versions[]` 每行继续提供 `operator` / `note` / `definitionSource` / `activatedAt`。

无人工参与的首启仍写 `system / startup-baseline`；`reset → 应用` 之后回到基线的那条 `ACTIVE` 记录，operator 是**执行恢复的人**，不回退成 `system`。

---

## 3. 内部事件接口（投递器 → 本体服务）

> **不暴露给浏览器**，仅 `backend/outbox/OutboxDispatcher` 调用；是「业务 → 底座」唯一的异步写入通道。

#### `POST /internal/events`
```jsonc
{
  "eventId": "9f1c7a2e-...",              // 唯一 ID（UUID），去重键
  "batchId": null,
  "aggregateType": "LESSON_SESSION",     // LESSON_SESSION / DEMO_STATE / BATCH / SNAPSHOT
  "aggregateId": "3",
  "changeKind": "ADJUST",                // ADJUST / BATCH_ADJUST / CLOCK / SYNC_TODAY / RESET / DELETE
  "revision": 85,                        // 业务提交时 system_revision（版本水位）
  "businessTime": "2026-11-14T10:15:00",// 演示业务时间（demoTime）
  "occurredAt": "2026-11-14T11:00:01",  // 真实处理时间
  "payload": {
    "sourceTable": "lesson_sessions", "sourcePk": "3", "sourceCode": "LS-003",
    "before": { "teacherId":1,"teacherName":"张明远","classroomId":1,"classroomName":"明理楼 101",
                "lessonDate":"2026-11-14","startTime":"10:00","endTime":"10:45","version":1 },
    "after":  { "teacherId":5,"teacherName":"陈思琪","classroomId":6,"classroomName":"多功能报告厅",
                "lessonDate":"2026-11-14","startTime":"15:30","endTime":"16:15","version":2 },
    "diff":  [ {"field":"teacherName","label":"授课教师","before":"张明远","after":"陈思琪"} ],
    "impact":{ "teacherIds":[1,5],"teacherNames":["张明远","陈思琪"],
               "classroomIds":[1,6],"classIds":[1],"studentIds":[1,2,3,4,5,6,7,8] },
    "reason": "教师参加教研，调至第七节并更换教室",
    "operator": "演示管理员"
  }
}
```

**响应**
```jsonc
// 底座单事务提交成功（含 processed_event 落库）后返回
{ "ack": true, "eventId": "9f1c7a2e-...", "persisted": true, "revision": 85 }
// 已处理（幂等）或旧事件（revision < applied_revision）→ 同样 ack，不重复落库
{ "ack": true, "eventId": "...", "persisted": false, "skipped": "DUPLICATE|STALE" }
// 处理失败 → HTTP 500，投递器按退避重试（业务事务不受影响）
{ "ack": false, "eventId": "...", "error": "..." }
```

| `changeKind` | payload 变体 |
| --- | --- |
| `ADJUST` | 单条 before/after/diff/impact（上例） |
| `BATCH_ADJUST` | `payload.items[]` 为上述结构数组，共用 `batchId`、`revision` |
| `CLOCK` | `{ demoAtBefore, demoAtAfter, running, anchorRealAt }`（影响动态结果，不产生对象属性变化） |
| `SYNC_TODAY` / `RESET` | `{ kind:"FULL_SNAPSHOT_HINT", scope:"ALL_LESSONS", deltaDays?, ... }`，底座触发定向 `catchUp(revision)` |
| `DELETE` | `{ sourceTable, sourcePk, tombstone:true }`，底座对象墓碑化 + 失效关系 |

**投递器行为契约**：仅当收到 `{ack:true, persisted:true}` 才把业务库 `outbox_events.status` 置 `DELIVERED`；否则 `attempts+1` + 指数退避，超过阈值置 `DEAD`（可经 `/admin/retry` 重放）。底座停机不影响业务提交（02 文档 §4.3）。

---

## 4. 契约与验收映射（速查）

| 验收/需求 | 相关端点 |
| --- | --- |
| 验收一 ≤5 秒多视角更新 / 刷新失败重试 | `/revision` + 各只读端点 + 前端「成功后 ack」协议 |
| 验收二 原业务停机仍可查 / 来源不可用 | `/sources/status`（`sourceUp=false`）、只读端点 |
| 验收三 6 种正确性 | `/admin/outbox`、`/admin/retry`、`/import/*`、`/reconcile`、`/rebuild` |
| 验收四 教室后接入零专用分支 | `/model/types`、`/search`、`/objects/{gid}`、`/graph`（无新端点） |
| 验收五 来源追溯与可重建 | `/model/mappings`、`/objects/{gid}`（`source`/`mappingVersion`）、`/relations`（`reason`）、`/rebuild` |
| 验收六 原业务回归 / 动态结果 | 原业务 `/api/*` 逐字不变；`/objects?queryTime=`、`/objects/{gid}?queryTime=` |
| E5 模型与接入管理四入口 | `/model/*`、`/import/full`、`/sync/tasks`、`/reconcile`、`/admin/retry` |
| F4 双契约分离 / 原契约不改 | 本文件 §1（原业务）+ §2（本体）；`docs/API-CONTRACT.md` 逐字未变 |

---

> **诚实边界**：本契约描述的本体 API 与新增 `batch-adjust` 均为**本轮设计与待实现**内容；`docs/API-CONTRACT.md` 既有端点契约逐字保持不变。达梦 / 其他 Java 系统适配仅在 02 文档 §8 列边界，本轮未测试、未实现。
