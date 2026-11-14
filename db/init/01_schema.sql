-- 学校业务对象全景演示：结构定义。
-- 只在数据卷为空时由 PostgreSQL 官方镜像执行；全部语句可重复执行。
-- 本体建模：对象（各实体表）+ 属性（列）+ 关系（外键与 lesson_sessions 汇聚表）。

CREATE TABLE IF NOT EXISTS schools (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    address     VARCHAR(255),
    phone       VARCHAR(64),
    slogan      VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS teachers (
    id                    BIGSERIAL PRIMARY KEY,
    code                  VARCHAR(32) NOT NULL UNIQUE,
    name                  VARCHAR(64) NOT NULL,
    school_id             BIGINT      NOT NULL REFERENCES schools(id),
    subject               VARCHAR(32) NOT NULL,
    title                 VARCHAR(64),
    phone                 VARCHAR(64),
    substitute_available  BOOLEAN     NOT NULL DEFAULT FALSE,
    status                VARCHAR(16) NOT NULL DEFAULT '在职'
);

CREATE TABLE IF NOT EXISTS courses (
    id       BIGSERIAL PRIMARY KEY,
    code     VARCHAR(32) NOT NULL UNIQUE,
    name     VARCHAR(64) NOT NULL,
    subject  VARCHAR(32) NOT NULL,
    credit   NUMERIC(3,1) NOT NULL DEFAULT 1,
    category VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS classrooms (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL UNIQUE,
    name       VARCHAR(128) NOT NULL,
    school_id  BIGINT       NOT NULL REFERENCES schools(id),
    building   VARCHAR(64),
    capacity   INTEGER      NOT NULL DEFAULT 40,
    room_type  VARCHAR(32)
);

CREATE TABLE IF NOT EXISTS classes (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    school_id       BIGINT       NOT NULL REFERENCES schools(id),
    grade           VARCHAR(32),
    head_teacher_id BIGINT       REFERENCES teachers(id),
    home_room_id    BIGINT       REFERENCES classrooms(id)
);

CREATE TABLE IF NOT EXISTS students (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(32) NOT NULL UNIQUE,
    name           VARCHAR(64) NOT NULL,
    class_id       BIGINT      NOT NULL REFERENCES classes(id),
    gender         VARCHAR(8),
    student_no     VARCHAR(32),
    guardian_phone VARCHAR(64),
    enroll_date    DATE
);

-- 「具体授课安排」是全系统唯一课表事实来源：一次课明确关联班级、课程、教师、教室、日期、起止时间。
CREATE TABLE IF NOT EXISTS lesson_sessions (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(32) NOT NULL UNIQUE,
    school_id     BIGINT      NOT NULL REFERENCES schools(id),
    class_id      BIGINT      NOT NULL REFERENCES classes(id),
    course_id     BIGINT      NOT NULL REFERENCES courses(id),
    teacher_id    BIGINT      NOT NULL REFERENCES teachers(id),
    classroom_id  BIGINT      NOT NULL REFERENCES classrooms(id),
    lesson_date   DATE        NOT NULL,
    start_time    TIME        NOT NULL,
    end_time      TIME        NOT NULL,
    period_name   VARCHAR(32),
    status        VARCHAR(16) NOT NULL DEFAULT '正常',
    version       INTEGER     NOT NULL DEFAULT 1,
    updated_at    TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ck_session_time CHECK (end_time > start_time)
);

CREATE INDEX IF NOT EXISTS idx_session_date      ON lesson_sessions (lesson_date);
CREATE INDEX IF NOT EXISTS idx_session_class     ON lesson_sessions (class_id);
CREATE INDEX IF NOT EXISTS idx_session_teacher   ON lesson_sessions (teacher_id);
CREATE INDEX IF NOT EXISTS idx_session_classroom ON lesson_sessions (classroom_id);

-- 变更记录保存快照，便于展示修改前后、原因、时间与受影响对象。
CREATE TABLE IF NOT EXISTS change_records (
    id                      BIGSERIAL PRIMARY KEY,
    session_id              BIGINT      NOT NULL REFERENCES lesson_sessions(id),
    session_code            VARCHAR(32) NOT NULL,
    school_id               BIGINT      NOT NULL,
    change_type             VARCHAR(32) NOT NULL,
    reason                  TEXT        NOT NULL,
    operator                VARCHAR(64) NOT NULL DEFAULT '演示管理员',
    occurred_at             TIMESTAMP   NOT NULL,
    before_json             JSONB       NOT NULL,
    after_json              JSONB       NOT NULL,
    diff_json               JSONB       NOT NULL,
    affected_json           JSONB       NOT NULL,
    affected_student_count  INTEGER     NOT NULL DEFAULT 0,
    is_seed                 BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_change_time ON change_records (occurred_at DESC);

-- 统一演示时钟：running 为 true 时按真实时钟推进，否则固定在 demo_at。
CREATE TABLE IF NOT EXISTS demo_state (
    id             INTEGER PRIMARY KEY DEFAULT 1,
    demo_at        TIMESTAMP   NOT NULL,
    running        BOOLEAN     NOT NULL DEFAULT FALSE,
    anchor_real_at TIMESTAMP   NOT NULL,
    updated_at     TIMESTAMP   NOT NULL DEFAULT now()
);

-- 全局数据版本号，供其他已打开视角短轮询发现变化。
CREATE TABLE IF NOT EXISTS system_revision (
    id         INTEGER PRIMARY KEY DEFAULT 1,
    revision   BIGINT    NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- 课表初始快照，仅用于「恢复演示数据」与「同步到今天」，不是业务事实来源。
CREATE TABLE IF NOT EXISTS lesson_session_seed (
    session_id   BIGINT PRIMARY KEY REFERENCES lesson_sessions(id),
    teacher_id   BIGINT NOT NULL,
    classroom_id BIGINT NOT NULL,
    lesson_date  DATE   NOT NULL,
    start_time   TIME   NOT NULL,
    end_time     TIME   NOT NULL
);
