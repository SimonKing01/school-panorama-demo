-- 全部为虚拟演示数据。lesson_date 使用 CURRENT_DATE，确保任意日期首次初始化都能得到「今天」的完整课表。
-- 演示时间默认固定在该日 10:15：正处于第三节课（10:00-10:45），下一节为 11:00-11:45。

INSERT INTO schools (id, code, name, address, phone, slogan) VALUES
 (1, 'SCH-01', '星海第一中学', '演示市星海路 88 号', '010-5555-0101', '让每个对象都被看见'),
 (2, 'SCH-02', '云岭实验学校', '演示市云岭大道 26 号', '010-5555-0202', '一所学校的全景视图')
ON CONFLICT DO NOTHING;

INSERT INTO teachers (id, code, name, school_id, subject, title, phone, substitute_available, status) VALUES
 (1,  'TCH-001', '张明远', 1, '语文', '高级教师', '138-0000-0001', FALSE, '在职'),
 (2,  'TCH-002', '李静怡', 1, '数学', '一级教师', '138-0000-0002', FALSE, '在职'),
 (3,  'TCH-003', '王海燕', 1, '英语', '高级教师', '138-0000-0003', FALSE, '在职'),
 (4,  'TCH-004', '赵建国', 1, '物理', '一级教师', '138-0000-0004', FALSE, '在职'),
 (5,  'TCH-005', '陈思琪', 1, '语文', '二级教师', '138-0000-0005', TRUE,  '在职'),
 (6,  'TCH-006', '周文博', 1, '数学', '二级教师', '138-0000-0006', TRUE,  '在职'),
 (7,  'TCH-007', '孙雅琴', 1, '体育', '一级教师', '138-0000-0007', FALSE, '在职'),
 (8,  'TCH-008', '吴嘉豪', 1, '语文', '一级教师', '138-0000-0008', FALSE, '在职'),
 (9,  'TCH-009', '郑丽华', 2, '语文', '高级教师', '138-0000-0009', FALSE, '在职'),
 (10, 'TCH-010', '黄志强', 2, '数学', '一级教师', '138-0000-0010', FALSE, '在职'),
 (11, 'TCH-011', '林婉如', 2, '英语', '一级教师', '138-0000-0011', FALSE, '在职'),
 (12, 'TCH-012', '何俊杰', 2, '化学', '高级教师', '138-0000-0012', FALSE, '在职'),
 (13, 'TCH-013', '刘晓芸', 2, '语文', '二级教师', '138-0000-0013', TRUE,  '在职'),
 (14, 'TCH-014', '马国栋', 2, '体育', '一级教师', '138-0000-0014', FALSE, '在职')
ON CONFLICT DO NOTHING;

INSERT INTO courses (id, code, name, subject, credit, category) VALUES
 (1, 'CRS-01', '语文',     '语文', 2.0, '基础学科'),
 (2, 'CRS-02', '数学',     '数学', 2.0, '基础学科'),
 (3, 'CRS-03', '英语',     '英语', 2.0, '基础学科'),
 (4, 'CRS-04', '物理',     '物理', 1.5, '理科'),
 (5, 'CRS-05', '化学',     '化学', 1.5, '理科'),
 (6, 'CRS-06', '体育',     '体育', 1.0, '艺体'),
 (7, 'CRS-07', '音乐',     '音乐', 1.0, '艺体'),
 (8, 'CRS-08', '信息技术', '信息技术', 1.0, '技术')
ON CONFLICT DO NOTHING;

INSERT INTO classrooms (id, code, name, school_id, building, capacity, room_type) VALUES
 (1,  'ROM-01', '明理楼 101',       1, '明理楼',   45, '普通教室'),
 (2,  'ROM-02', '明理楼 102',       1, '明理楼',   45, '普通教室'),
 (3,  'ROM-03', '明理楼 103',       1, '明理楼',   45, '空闲备用教室'),
 (4,  'ROM-04', '实验楼 201 物理实验室', 1, '实验楼', 48, '实验室'),
 (5,  'ROM-05', '体育馆',           1, '体育馆',   60, '体育场馆'),
 (6,  'ROM-06', '多功能报告厅',     1, '综合楼',   80, '空闲备用教室'),
 (7,  'ROM-07', '致远楼 201',       2, '致远楼',   45, '普通教室'),
 (8,  'ROM-08', '致远楼 202',       2, '致远楼',   45, '普通教室'),
 (9,  'ROM-09', '科创楼 301 化学实验室', 2, '科创楼', 48, '实验室'),
 (10, 'ROM-10', '操场',             2, '操场',     60, '体育场馆'),
 (11, 'ROM-11', '阶梯教室',         2, '综合楼',   80, '空闲备用教室')
ON CONFLICT DO NOTHING;

INSERT INTO classes (id, code, name, school_id, grade, head_teacher_id, home_room_id) VALUES
 (1, 'CLS-01', '初一（1）班', 1, '初一', 1,  1),
 (2, 'CLS-02', '初一（2）班', 1, '初一', 2,  2),
 (3, 'CLS-03', '初二（1）班', 2, '初二', 9,  7),
 (4, 'CLS-04', '初二（2）班', 2, '初二', 10, 8)
ON CONFLICT DO NOTHING;

-- 32 名学生，每班 8 人；学生只通过 class_id 继承班级课表。
INSERT INTO students (id, code, name, class_id, gender, student_no, guardian_phone, enroll_date) VALUES
 (1,  'STU-0001', '陈一诺', 1, '女', '20260101', '139-0000-0101', CURRENT_DATE - 280),
 (2,  'STU-0002', '林子涵', 1, '男', '20260102', '139-0000-0102', CURRENT_DATE - 280),
 (3,  'STU-0003', '王思远', 1, '男', '20260103', '139-0000-0103', CURRENT_DATE - 280),
 (4,  'STU-0004', '赵欣怡', 1, '女', '20260104', '139-0000-0104', CURRENT_DATE - 280),
 (5,  'STU-0005', '刘沐辰', 1, '男', '20260105', '139-0000-0105', CURRENT_DATE - 280),
 (6,  'STU-0006', '周语桐', 1, '女', '20260106', '139-0000-0106', CURRENT_DATE - 280),
 (7,  'STU-0007', '吴嘉树', 1, '男', '20260107', '139-0000-0107', CURRENT_DATE - 280),
 (8,  'STU-0008', '徐若曦', 1, '女', '20260108', '139-0000-0108', CURRENT_DATE - 280),
 (9,  'STU-0009', '孙浩然', 2, '男', '20260201', '139-0000-0201', CURRENT_DATE - 280),
 (10, 'STU-0010', '马诗涵', 2, '女', '20260202', '139-0000-0202', CURRENT_DATE - 280),
 (11, 'STU-0011', '朱奕辰', 2, '男', '20260203', '139-0000-0203', CURRENT_DATE - 280),
 (12, 'STU-0012', '胡可欣', 2, '女', '20260204', '139-0000-0204', CURRENT_DATE - 280),
 (13, 'STU-0013', '郭子墨', 2, '男', '20260205', '139-0000-0205', CURRENT_DATE - 280),
 (14, 'STU-0014', '何雨萱', 2, '女', '20260206', '139-0000-0206', CURRENT_DATE - 280),
 (15, 'STU-0015', '高梓轩', 2, '男', '20260207', '139-0000-0207', CURRENT_DATE - 280),
 (16, 'STU-0016', '罗安然', 2, '女', '20260208', '139-0000-0208', CURRENT_DATE - 280),
 (17, 'STU-0017', '梁俊熙', 3, '男', '20250301', '139-0000-0301', CURRENT_DATE - 640),
 (18, 'STU-0018', '宋佳宁', 3, '女', '20250302', '139-0000-0302', CURRENT_DATE - 640),
 (19, 'STU-0019', '谢明哲', 3, '男', '20250303', '139-0000-0303', CURRENT_DATE - 640),
 (20, 'STU-0020', '唐婉清', 3, '女', '20250304', '139-0000-0304', CURRENT_DATE - 640),
 (21, 'STU-0021', '韩知远', 3, '男', '20250305', '139-0000-0305', CURRENT_DATE - 640),
 (22, 'STU-0022', '曹语嫣', 3, '女', '20250306', '139-0000-0306', CURRENT_DATE - 640),
 (23, 'STU-0023', '邓博文', 3, '男', '20250307', '139-0000-0307', CURRENT_DATE - 640),
 (24, 'STU-0024', '许静宜', 3, '女', '20250308', '139-0000-0308', CURRENT_DATE - 640),
 (25, 'STU-0025', '傅晨阳', 4, '男', '20250401', '139-0000-0401', CURRENT_DATE - 640),
 (26, 'STU-0026', '沈依诺', 4, '女', '20250402', '139-0000-0402', CURRENT_DATE - 640),
 (27, 'STU-0027', '曾梓睿', 4, '男', '20250403', '139-0000-0403', CURRENT_DATE - 640),
 (28, 'STU-0028', '彭乐瑶', 4, '女', '20250404', '139-0000-0404', CURRENT_DATE - 640),
 (29, 'STU-0029', '萧景行', 4, '男', '20250405', '139-0000-0405', CURRENT_DATE - 640),
 (30, 'STU-0030', '田诗琪', 4, '女', '20250406', '139-0000-0406', CURRENT_DATE - 640),
 (31, 'STU-0031', '董宇航', 4, '男', '20250407', '139-0000-0407', CURRENT_DATE - 640),
 (32, 'STU-0032', '袁芷若', 4, '女', '20250408', '139-0000-0408', CURRENT_DATE - 640)
ON CONFLICT DO NOTHING;

-- 完整演示课表：4 个班 × 6 节 = 24 次授课安排。
-- 第七节 15:30-16:15 全部空闲；ROM-03 / ROM-06 / ROM-11 全天空闲；TCH-005 / TCH-006 为可代课教师。
INSERT INTO lesson_sessions
 (id, code, school_id, class_id, course_id, teacher_id, classroom_id, lesson_date, start_time, end_time, period_name) VALUES
 (1,  'LS-001', 1, 1, 1, 1,  1,  CURRENT_DATE, TIME '08:00', TIME '08:45', '第一节'),
 (2,  'LS-002', 1, 1, 2, 2,  1,  CURRENT_DATE, TIME '09:00', TIME '09:45', '第二节'),
 (3,  'LS-003', 1, 1, 1, 1,  1,  CURRENT_DATE, TIME '10:00', TIME '10:45', '第三节'),
 (4,  'LS-004', 1, 1, 4, 4,  4,  CURRENT_DATE, TIME '11:00', TIME '11:45', '第四节'),
 (5,  'LS-005', 1, 1, 6, 7,  5,  CURRENT_DATE, TIME '13:30', TIME '14:15', '第五节'),
 (6,  'LS-006', 1, 1, 3, 3,  1,  CURRENT_DATE, TIME '14:30', TIME '15:15', '第六节'),
 (7,  'LS-007', 1, 2, 2, 2,  2,  CURRENT_DATE, TIME '08:00', TIME '08:45', '第一节'),
 (8,  'LS-008', 1, 2, 3, 3,  2,  CURRENT_DATE, TIME '09:00', TIME '09:45', '第二节'),
 (9,  'LS-009', 1, 2, 1, 8,  2,  CURRENT_DATE, TIME '10:00', TIME '10:45', '第三节'),
 (10, 'LS-010', 1, 2, 2, 2,  2,  CURRENT_DATE, TIME '11:00', TIME '11:45', '第四节'),
 (11, 'LS-011', 1, 2, 4, 4,  2,  CURRENT_DATE, TIME '13:30', TIME '14:15', '第五节'),
 (12, 'LS-012', 1, 2, 6, 7,  5,  CURRENT_DATE, TIME '14:30', TIME '15:15', '第六节'),
 (13, 'LS-013', 2, 3, 1, 9,  7,  CURRENT_DATE, TIME '08:00', TIME '08:45', '第一节'),
 (14, 'LS-014', 2, 3, 2, 10, 7,  CURRENT_DATE, TIME '09:00', TIME '09:45', '第二节'),
 (15, 'LS-015', 2, 3, 3, 11, 7,  CURRENT_DATE, TIME '10:00', TIME '10:45', '第三节'),
 (16, 'LS-016', 2, 3, 5, 12, 9,  CURRENT_DATE, TIME '11:00', TIME '11:45', '第四节'),
 (17, 'LS-017', 2, 3, 6, 14, 10, CURRENT_DATE, TIME '13:30', TIME '14:15', '第五节'),
 (18, 'LS-018', 2, 3, 2, 10, 7,  CURRENT_DATE, TIME '14:30', TIME '15:15', '第六节'),
 (19, 'LS-019', 2, 4, 2, 10, 8,  CURRENT_DATE, TIME '08:00', TIME '08:45', '第一节'),
 (20, 'LS-020', 2, 4, 1, 9,  8,  CURRENT_DATE, TIME '09:00', TIME '09:45', '第二节'),
 (21, 'LS-021', 2, 4, 5, 12, 9,  CURRENT_DATE, TIME '10:00', TIME '10:45', '第三节'),
 (22, 'LS-022', 2, 4, 3, 11, 8,  CURRENT_DATE, TIME '11:00', TIME '11:45', '第四节'),
 (23, 'LS-023', 2, 4, 1, 13, 8,  CURRENT_DATE, TIME '13:30', TIME '14:15', '第五节'),
 (24, 'LS-024', 2, 4, 6, 14, 10, CURRENT_DATE, TIME '14:30', TIME '15:15', '第六节')
ON CONFLICT DO NOTHING;

INSERT INTO lesson_session_seed (session_id, teacher_id, classroom_id, lesson_date, start_time, end_time)
SELECT id, teacher_id, classroom_id, lesson_date, start_time, end_time FROM lesson_sessions
ON CONFLICT DO NOTHING;

INSERT INTO demo_state (id, demo_at, running, anchor_real_at)
VALUES (1, CURRENT_DATE::timestamp + INTERVAL '10 hours 15 minutes', FALSE, now())
ON CONFLICT DO NOTHING;

INSERT INTO system_revision (id, revision) VALUES (1, 1) ON CONFLICT DO NOTHING;

-- 预置变更记录，便于首次打开就能看到「修改前后 / 原因 / 时间 / 受影响对象」。
INSERT INTO change_records
 (id, session_id, session_code, school_id, change_type, reason, operator, occurred_at,
  before_json, after_json, diff_json, affected_json, affected_student_count, is_seed) VALUES
 (1, 5, 'LS-005', 1, '教室调整', '体育馆临时维护，体育课改在操场旁集合后于原馆上课，已提前通知', '课务管理员',
  CURRENT_DATE::timestamp + INTERVAL '8 hours 20 minutes',
  jsonb_build_object('className','初一（1）班','courseName','体育','teacherName','孙雅琴','classroomName','体育馆','lessonDate',CURRENT_DATE::text,'startTime','13:30','endTime','14:15'),
  jsonb_build_object('className','初一（1）班','courseName','体育','teacherName','孙雅琴','classroomName','体育馆','lessonDate',CURRENT_DATE::text,'startTime','13:30','endTime','14:15'),
  '[{"field":"notice","label":"课前通知","before":"未通知","after":"已通知全班"}]'::jsonb,
  '{"studentCount":8,"classIds":[1],"classNames":["初一（1）班"],"teacherIds":[7],"teacherNames":["孙雅琴"],"studentIds":[1,2,3,4,5,6,7,8]}'::jsonb,
  8, TRUE),
 (2, 16, 'LS-016', 2, '教室调整', '化学实验器材搬到科创楼 301，教室由普通教室改为化学实验室', '课务管理员',
  CURRENT_DATE::timestamp + INTERVAL '9 hours 5 minutes',
  jsonb_build_object('className','初二（1）班','courseName','化学','teacherName','何俊杰','classroomName','致远楼 201','lessonDate',CURRENT_DATE::text,'startTime','11:00','endTime','11:45'),
  jsonb_build_object('className','初二（1）班','courseName','化学','teacherName','何俊杰','classroomName','科创楼 301 化学实验室','lessonDate',CURRENT_DATE::text,'startTime','11:00','endTime','11:45'),
  '[{"field":"classroomName","label":"教室","before":"致远楼 201","after":"科创楼 301 化学实验室"}]'::jsonb,
  '{"studentCount":8,"classIds":[3],"classNames":["初二（1）班"],"teacherIds":[12],"teacherNames":["何俊杰"],"studentIds":[17,18,19,20,21,22,23,24]}'::jsonb,
  8, TRUE),
 (3, 20, 'LS-020', 2, '编排确认', '开学课表编排完成，确认第二节语文授课教师与教室', '课务管理员',
  CURRENT_DATE::timestamp + INTERVAL '9 hours 40 minutes',
  jsonb_build_object('className','初二（2）班','courseName','语文','teacherName','待定','classroomName','待定','lessonDate',CURRENT_DATE::text,'startTime','09:00','endTime','09:45'),
  jsonb_build_object('className','初二（2）班','courseName','语文','teacherName','郑丽华','classroomName','致远楼 202','lessonDate',CURRENT_DATE::text,'startTime','09:00','endTime','09:45'),
  '[{"field":"teacherName","label":"教师","before":"待定","after":"郑丽华"},{"field":"classroomName","label":"教室","before":"待定","after":"致远楼 202"}]'::jsonb,
  '{"studentCount":8,"classIds":[4],"classNames":["初二（2）班"],"teacherIds":[9],"teacherNames":["郑丽华"],"studentIds":[25,26,27,28,29,30,31,32]}'::jsonb,
  8, TRUE)
ON CONFLICT DO NOTHING;

-- 显式 ID 插入后同步序列，避免后续新增记录主键冲突。
SELECT setval(pg_get_serial_sequence('schools','id'),          (SELECT max(id) FROM schools));
SELECT setval(pg_get_serial_sequence('teachers','id'),         (SELECT max(id) FROM teachers));
SELECT setval(pg_get_serial_sequence('courses','id'),          (SELECT max(id) FROM courses));
SELECT setval(pg_get_serial_sequence('classrooms','id'),       (SELECT max(id) FROM classrooms));
SELECT setval(pg_get_serial_sequence('classes','id'),          (SELECT max(id) FROM classes));
SELECT setval(pg_get_serial_sequence('students','id'),         (SELECT max(id) FROM students));
SELECT setval(pg_get_serial_sequence('lesson_sessions','id'),  (SELECT max(id) FROM lesson_sessions));
SELECT setval(pg_get_serial_sequence('change_records','id'),   (SELECT max(id) FROM change_records));
