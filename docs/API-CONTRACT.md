# 前后端接口契约（唯一版本，修改需主 Agent 确认）

- 后端监听 `8080`，所有接口前缀 `/api`；前端 nginx 把 `/api` 反向代理到 `backend:8080`。
- 全部 `application/json;charset=UTF-8`。日期 `yyyy-MM-dd`，时间 `HH:mm`，时间戳 `yyyy-MM-dd'T'HH:mm:ss`（无时区，按演示本地时间解释）。
- 所有接口都是演示用匿名接口，不做鉴权。
- 除 `/api/health` 外，所有响应都应带字段 `demoTime`（当前统一演示时间戳）。
- 错误统一：`{"code":"...","message":"...","conflicts":[...],"demoTime":"..."}`，HTTP 400（校验失败）/404（不存在）/500（服务异常）。
  前端展示 `message`，并把 `conflicts[].message` 逐条列出。

## 公共结构

```jsonc
// Ref
{ "id": 1, "code": "SCH-01", "name": "星海第一中学" }

// LessonView —— 一次具体授课安排（所有视角共用这一个对象）
{
  "id": 3, "code": "LS-003",
  "schoolId": 1, "schoolName": "星海第一中学",
  "classId": 1, "className": "初一（1）班",
  "courseId": 1, "courseName": "语文", "subject": "语文",
  "teacherId": 1, "teacherName": "张明远",
  "classroomId": 1, "classroomName": "明理楼 101",
  "lessonDate": "2026-11-14", "startTime": "10:00", "endTime": "10:45",
  "periodName": "第三节",
  "studentCount": 8,
  "status": "正在上课"          // 正在上课 | 即将开始 | 已结束
}

// ObjectRow —— 全局列表 / 搜索结果的一行
{
  "type": "student",           // student | teacher | class | school | course | classroom
  "id": 1, "code": "STU-0001", "name": "陈一诺",
  "schoolId": 1, "schoolName": "星海第一中学",
  "classId": 1, "className": "初一（1）班",
  "subject": "语文",           // 教师、课程有值；其他可空
  "extra": "女 · 学号 20260101",   // 一行补充属性，可空字符串
  "currentLesson": null,        // LessonView 或 null
  "nextLesson": null            // LessonView 或 null
}
```

`currentLesson` = 满足 `startAt <= demoTime < endAt` 的授课安排；
`nextLesson` = `startAt > demoTime` 中开始时间最早的一次；
两者都为空要由前端显示「当前无课 / 今日课程已全部结束」的空状态。
学生通过 `students.class_id = lesson_sessions.class_id` 继承班级课表，不得另建关联表。

## 接口

### 1. 健康检查
`GET /api/health` → `{"status":"UP","db":"UP","demoTime":"...","revision":1}`

### 2. 元数据（筛选项）
`GET /api/meta/options` →
```jsonc
{
  "demoTime": "...",
  "schools":  [{"id":1,"code":"SCH-01","name":"星海第一中学"}],
  "classes":  [{"id":1,"code":"CLS-01","name":"初一（1）班","schoolId":1}],
  "teachers": [{"id":1,"code":"TCH-001","name":"张明远","schoolId":1,"subject":"语文","substituteAvailable":false}],
  "courses":  [{"id":1,"code":"CRS-01","name":"语文","subject":"语文"}],
  "classrooms":[{"id":1,"code":"ROM-01","name":"明理楼 101","schoolId":1,"capacity":45}],
  "subjects": ["语文","数学"]
}
```

### 3. 演示时钟
- `GET /api/demo/clock` →
```jsonc
{"demoTime":"2026-11-14T10:15:00","running":false,"lessonDate":"2026-11-14",
 "lessonEndTime":"15:15","presets":[{"label":"上课前 07:30","demoTime":"..."},
 {"label":"第三节课中 10:15","demoTime":"..."},{"label":"第三节课结束 10:45","demoTime":"..."},
 {"label":"全部课程结束 17:00","demoTime":"..."}]}
```
  `presets` 的 demoTime 必须是当天完整时间戳。
- `POST /api/demo/clock`，body 支持任一字段：
```jsonc
{"demoTime":"2026-11-14T10:45:00", "running":false, "minuteOffset":30}
```
  `demoTime` 优先；否则用 `minuteOffset` 在当前演示时间上偏移；`running=true` 时演示时间随真实时间推进。
  成功返回同 GET，并递增 revision。
- `POST /api/demo/clock/sync-today` → 把所有授课安排日期、演示时钟、预置变更记录时间整体平移到今天（单事务），返回同 GET，并递增 revision。
- `POST /api/demo/reset` → 恢复课表初始快照、清掉演示期间新增的变更记录、把演示时间恢复到当天 10:15，返回同 GET，并递增 revision。用于反复验收。

### 4. 全局总览
`GET /api/overview` →
```jsonc
{
 "demoTime":"...",
 "counts":{"schools":2,"teachers":14,"classes":4,"students":32,"courses":8,
           "classrooms":11,"sessionsToday":24,"changes":3,"currentLessons":4},
 "perSchool":[{"schoolId":1,"schoolName":"星海第一中学","classCount":2,"teacherCount":8,
               "studentCount":16,"sessionsToday":12,"currentLessonCount":2}],
 "currentLessons":[LessonView],
 "todayLessons":[LessonView],
 "recentChanges":[ChangeItem]
}
```
统计项可点击：schools/teachers/classes/students → 全局列表对应类型（带筛选）；sessionsToday/currentLessons → 课表管理；changes → 变更记录。

### 5. 对象查询（无菜单直达）
`GET /api/search?q=张&type=all&schoolId=&classId=`
- `q` 匹配学生/教师的**姓名或编号**（模糊、忽略大小写），为空时返回按 type 过滤的前 N 条。
- `type`: `all` | `student` | `teacher`；`schoolId`、`classId` 可选筛选（`classId` 只对教师按任教班级过滤，对学生按所在班级过滤）。
→ `{"demoTime":"...","total":n,"items":[ObjectRow]}`（currentLesson / nextLesson 必须有值）

### 6. 全局列表
`GET /api/objects?type=student&schoolId=&classId=&q=`
- `type`: `student|teacher|class|school|course|classroom`；`q` 可选按姓名/编号/名称模糊过滤。
→ `{"demoTime":"...","type":"student","total":n,"items":[ObjectRow]}`
- 每行的 `currentLesson` / `nextLesson` 要带教师、时间、地点；`extra` 展示归属等补充信息。

### 7. 对象详情
`GET /api/objects/{type}/{id}` →
```jsonc
{
 "demoTime":"...",
 "type":"student", "id":1, "code":"STU-0001", "name":"陈一诺",
 "school": {"id":1,"code":"SCH-01","name":"星海第一中学","type":"school"},
 "attributes":[{"label":"编号","value":"STU-0001"},{"label":"性别","value":"女"}],
 "relations":[{"type":"class","id":1,"code":"CLS-01","name":"初一（1）班","role":"所在班级"},
              {"type":"teacher","id":1,"code":"TCH-001","name":"张明远","role":"班主任"}],
 "currentLesson": LessonView|null,
 "nextLesson": LessonView|null,
 "schedule":[LessonView],
 "recentChanges":[ChangeItem],
 "studentCount":8   // 班级/学校详情给总人数；其他可为 0
}
```
`relations` 中的对象必须可点击跳转到 `/object/{type}/{id}`。各类对象的 relations 至少包含：
- student：所在班级、所在学校、班主任、当日任课教师（去重）
- teacher：所属学校、任教班级（从课表推断）、所授课程
- class：所属学校、班主任、本班学生（前 8 条）、任课教师
- school：下属班级、教师、教室
- course：当天安排该课程的班级与教师
- classroom：所属学校、当天使用该教室的班级

### 8. 课表管理
`GET /api/schedule?schoolId=&classId=&teacherId=&date=&status=` →
```jsonc
{"demoTime":"...","total":24,"items":[LessonView],
 "stats":{"total":24,"ongoing":4,"upcoming":16,"finished":4}}
```
`date` 为 `yyyy-MM-dd`，不传则不过滤日期；`status` 可为 `正在上课|即将开始|已结束`。

`GET /api/schedule/{id}` →
```jsonc
{"demoTime":"...","lesson":LessonView,
 "before":{"teacherId":1,"teacherName":"张明远","classroomId":1,"classroomName":"明理楼 101",
           "lessonDate":"2026-11-14","startTime":"10:00","endTime":"10:45"},
 "candidates":{"teachers":[{"id":1,"code":"TCH-001","name":"张明远","schoolName":"星海第一中学","subject":"语文","substituteAvailable":false,"busy":false}],
               "classrooms":[{"id":1,"code":"ROM-01","name":"明理楼 101","schoolName":"星海第一中学","capacity":45,"busy":false}],
               "freeSlots":[{"label":"第七节 15:30-16:15","startTime":"15:30","endTime":"16:15"}]}}
```
`busy` 表示该候选在**本次课当前时段**是否已被其他安排占用；`candidates.teachers` 只包含与本次课同一学校、科目相同的教师 + 原教师（另附 `crossSchoolTeachers` / `wrongSubjectTeachers` 不是必须，可选）。

`POST /api/schedule/{id}/preview`（不写入）与 `POST /api/schedule/{id}/adjust`（写入）共用 body：
```jsonc
{"teacherId":5,"classroomId":6,"lessonDate":"2026-11-14",
 "startTime":"15:30","endTime":"16:15","reason":"教师参加教研，调至第七节并更换教室"}
```
preview 返回：
```jsonc
{"demoTime":"...","valid":true,
 "conflicts":[{"type":"TEACHER_CONFLICT","message":"教师吴嘉豪 10:00-10:45 已在初一（2）班授课"}],
 "before":{...}, "after":{...},
 "diff":[{"field":"teacherName","label":"授课教师","before":"张明远","after":"陈思琪"}],
 "impact":{"studentCount":8,"studentIds":[...],"classIds":[1],"classNames":["初一（1）班"],
           "teacherIds":[1,5],"teacherNames":["张明远","陈思琪"],
           "classroomIds":[1,6],"classroomNames":["明理楼 101","多功能报告厅"]}}
```
校验规则（按顺序，`conflicts` 可含多条）：
1. `reason` 不能为空 → `REASON_REQUIRED`「必须填写调整原因」。
2. 时间合法（结束 > 开始）→ `TIME_INVALID`。
3. 教师所属学校必须等于班级所属学校 → `TEACHER_SCHOOL_MISMATCH`「教师 X 属于…，与授课班级所属学校…不一致」。
4. 教师授课科目必须等于课程科目 → `TEACHER_SUBJECT_MISMATCH`「教师 X 授课科目为…，与课程《语文》科目…不一致」。
5. 教室必须属于同一学校 → `CLASSROOM_SCHOOL_MISMATCH`。
6. 教师时间冲突（排除本次课自身）→ `TEACHER_CONFLICT`，消息含教师名、冲突时段、冲突班级。
7. 班级时间冲突（排除本次课自身）→ `CLASS_CONFLICT`，消息含班级名与冲突课程。
8. 教室时间冲突（排除本次课自身）→ `CLASSROOM_CONFLICT`，消息含教室名与冲突班级。
时间重叠判定：`newStart < other.endTime AND newEnd > other.startTime`（同一 lessonDate）。

`adjust` 成功返回（HTTP 200）：
```jsonc
{"demoTime":"...","lesson":LessonView,"change":ChangeItem,"impact":{...},"diff":[...],"revision":12}
```
`adjust` 失败返回 HTTP 400，body 与 preview 相同结构（`valid:false` + 具体 conflicts），**不得产生任何写入**。
成功必须在**同一事务**内：更新 lesson_sessions（teacher_id / classroom_id / lesson_date / start_time / end_time / version+1 / updated_at）+ 插入 change_records + 递增 system_revision.revision。
并发调课需在事务开始加 `SELECT pg_advisory_xact_lock(918273)` 串行化，避免并发漏判冲突。

### 9. 变更记录
`GET /api/changes?schoolId=&limit=50` → `{"demoTime":"...","total":n,"items":[ChangeItem]}`
`GET /api/changes/{id}` → `ChangeItem` 详情（含 impact、diff、可直接跳转的对象链接信息）

```jsonc
// ChangeItem
{"id":12,"sessionId":3,"sessionCode":"LS-003","schoolId":1,
 "changeType":"调课","reason":"教师参加教研…","operator":"演示管理员",
 "occurredAt":"2026-11-14T10:15:00",
 "before":{...}, "after":{...}, "diff":[...],
 "impact":{"studentCount":8,"studentIds":[...],"classIds":[1],"classNames":["初一（1）班"],
           "teacherIds":[1,5],"teacherNames":["张明远","陈思琪"],
           "classroomIds":[1,6],"classroomNames":["明理楼 101","多功能报告厅"]},
 "affectedStudentCount":8,
 "className":"初一（1）班","courseName":"语文"}
```

### 10. 短轮询
`GET /api/revision` → `{"revision":12,"demoTime":"...","running":false}`
前端每 3 秒轮询；revision 或 demoTime 变化时刷新当前视角（保证 5 秒内同步）。

## 一致性硬要求

- 总览、搜索、列表、详情、课表、变更记录全部读取同一张 `lesson_sessions`，通过同一个共享查询服务计算 `currentLesson` / `nextLesson`，禁止任何视角缓存业务事实。
- 学生不单独存课表；学生视角的课表 = 所在班级的课表。
- 调课成功后，学生、原教师、新教师、班级、课表、总览、变更记录立即返回同一结果。
- 校验失败时课表与变更记录都不变，revision 也不变。

## 演示数据速查（供测试脚本使用）

| 用途 | 数据 |
|---|---|
| 待调整课 | `LS-003`（id=3），初一（1）班 语文 张明远(TCH-001) 明理楼101 10:00-10:45 |
| 可代课教师 | `TCH-005` 陈思琪（星海一中 · 语文 · 全天空闲）、`TCH-006` 周文博（数学） |
| 空闲教室 | `ROM-03`、`ROM-06`（星海一中）、`ROM-11`（云岭实验） |
| 空闲时段 | 第七节 15:30-16:15（全体空闲） |
| 跨校教师（学校校验失败） | `TCH-013` 刘晓芸（云岭实验 · 语文）用于 LS-003 |
| 科目不符（科目校验失败） | `TCH-002` 李静怡（数学）用于 LS-003 |
| 教师冲突 | `TCH-008` 吴嘉豪（语文）10:00-10:45 正在教 LS-009 |
| 教室冲突 | `ROM-02` 明理楼102 10:00-10:45 已被 LS-009 占用 |
| 班级冲突 | 把 LS-003 时间改成 11:00-11:45（初一（1）班已有 LS-004 物理） |
| 学生示例 | `STU-0001` 陈一诺（初一（1）班）、`STU-0017` 梁俊熙（初二（1）班） |
| 演示时间边界 | 07:30 上课前 / 10:15 第三节课中 / 10:45 第三节结束 / 17:00 全部结束 |
