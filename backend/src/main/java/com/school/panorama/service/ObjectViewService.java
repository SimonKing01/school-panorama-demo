package com.school.panorama.service;

import com.school.panorama.common.ApiException;
import com.school.panorama.common.Resp;
import com.school.panorama.dto.LessonView;
import com.school.panorama.dto.ObjectRow;
import com.school.panorama.entity.Classroom;
import com.school.panorama.entity.Clazz;
import com.school.panorama.entity.Course;
import com.school.panorama.entity.School;
import com.school.panorama.entity.Student;
import com.school.panorama.entity.Teacher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** 元数据、搜索、全局列表、对象详情的响应组装；课表事实全部来自共享 BusinessQueryService。 */
@Service
public class ObjectViewService {

    @Autowired private CatalogService catalog;
    @Autowired private BusinessQueryService business;
    @Autowired private DemoClockService demoClock;
    @Autowired private ChangeService changeService;

    public Map<String, Object> metaOptions() {
        List<Map<String, Object>> schools = new ArrayList<>();
        for (School s : catalog.schools()) {
            schools.add(Resp.map("id", s.id, "code", s.code, "name", s.name));
        }
        List<Map<String, Object>> classes = new ArrayList<>();
        for (Clazz c : catalog.classes()) {
            classes.add(Resp.map("id", c.id, "code", c.code, "name", c.name, "schoolId", c.schoolId));
        }
        List<Map<String, Object>> teachers = new ArrayList<>();
        for (Teacher t : catalog.teachers()) {
            teachers.add(Resp.map("id", t.id, "code", t.code, "name", t.name,
                    "schoolId", t.schoolId, "subject", t.subject,
                    "substituteAvailable", Boolean.TRUE.equals(t.substituteAvailable)));
        }
        List<Map<String, Object>> courses = new ArrayList<>();
        for (Course c : catalog.courses()) {
            courses.add(Resp.map("id", c.id, "code", c.code, "name", c.name, "subject", c.subject));
        }
        List<Map<String, Object>> classrooms = new ArrayList<>();
        for (Classroom c : catalog.classrooms()) {
            classrooms.add(Resp.map("id", c.id, "code", c.code, "name", c.name,
                    "schoolId", c.schoolId, "capacity", c.capacity));
        }
        List<String> subjects = catalog.courses().stream()
                .map(c -> c.subject).distinct().sorted().toList();
        return Resp.map("demoTime", demoClock.demoTimeString(),
                "schools", schools, "classes", classes, "teachers", teachers,
                "courses", courses, "classrooms", classrooms, "subjects", subjects);
    }

    // ---------- 搜索 ----------

    public Map<String, Object> search(String q, String type, Long schoolId, Long classId) {
        LocalDateTime dt = demoClock.now();
        List<LessonView> lessons = business.allLessons();
        String kw = q == null ? "" : q.trim().toLowerCase();
        boolean all = type == null || type.isEmpty() || "all".equals(type);
        boolean wantStudent = all || "student".equals(type);
        boolean wantTeacher = all || "teacher".equals(type);
        List<ObjectRow> items = new ArrayList<>();
        if (wantStudent) {
            for (Student s : catalog.students()) {
                Clazz clz = catalog.clazz(s.classId);
                if (schoolId != null && (clz == null || !Objects.equals(clz.schoolId, schoolId))) continue;
                if (classId != null && !Objects.equals(s.classId, classId)) continue;
                if (!kw.isEmpty() && !match(s.name, s.code, kw)) continue;
                items.add(studentRow(s, clz, dt, lessons));
            }
        }
        if (wantTeacher) {
            for (Teacher t : catalog.teachers()) {
                if (schoolId != null && !Objects.equals(t.schoolId, schoolId)) continue;
                if (classId != null && !teachesClass(t.id, classId, lessons)) continue;
                if (!kw.isEmpty() && !match(t.name, t.code, kw)) continue;
                items.add(teacherRow(t, dt, lessons));
            }
        }
        // q 为空时按 type 过滤返回前 N 条，避免一次返回全量
        if (items.size() > 50) {
            items = new ArrayList<>(items.subList(0, 50));
        }
        return Resp.map("demoTime", Resp.ts(dt), "total", items.size(), "items", items);
    }

    // ---------- 全局列表 ----------

    public Map<String, Object> objects(String type, Long schoolId, Long classId, String q) {
        LocalDateTime dt = demoClock.now();
        List<LessonView> lessons = business.allLessons();
        String kw = q == null ? "" : q.trim().toLowerCase();
        List<ObjectRow> items = new ArrayList<>();
        switch (type == null ? "" : type) {
            case "student": {
                for (Student s : catalog.students()) {
                    Clazz clz = catalog.clazz(s.classId);
                    if (schoolId != null && (clz == null || !Objects.equals(clz.schoolId, schoolId))) continue;
                    if (classId != null && !Objects.equals(s.classId, classId)) continue;
                    if (!kw.isEmpty() && !match(s.name, s.code, kw)) continue;
                    items.add(studentRow(s, clz, dt, lessons));
                }
                break;
            }
            case "teacher": {
                for (Teacher t : catalog.teachers()) {
                    if (schoolId != null && !Objects.equals(t.schoolId, schoolId)) continue;
                    if (classId != null && !teachesClass(t.id, classId, lessons)) continue;
                    if (!kw.isEmpty() && !match(t.name, t.code, kw)) continue;
                    items.add(teacherRow(t, dt, lessons));
                }
                break;
            }
            case "class": {
                for (Clazz c : catalog.classes()) {
                    if (schoolId != null && !Objects.equals(c.schoolId, schoolId)) continue;
                    if (classId != null && !Objects.equals(c.id, classId)) continue;
                    if (!kw.isEmpty() && !match(c.name, c.code, kw)) continue;
                    String head = c.headTeacherId == null ? "" : nameOfTeacher(c.headTeacherId);
                    String extra = (c.grade == null ? "" : c.grade) + " · 班主任 " + head;
                    items.add(row("class", c.id, c.code, c.name, c.schoolId, c.id, null, extra,
                            l -> l.classId == c.id, dt, lessons));
                }
                break;
            }
            case "school": {
                for (School s : catalog.schools()) {
                    if (schoolId != null && !Objects.equals(s.id, schoolId)) continue;
                    if (!kw.isEmpty() && !match(s.name, s.code, kw)) continue;
                    String extra = "地址 " + (s.address == null ? "" : s.address);
                    items.add(row("school", s.id, s.code, s.name, s.id, null, null, extra,
                            l -> l.schoolId == s.id, dt, lessons));
                }
                break;
            }
            case "course": {
                for (Course c : catalog.courses()) {
                    if (!kw.isEmpty() && !match(c.name, c.code, kw)) continue;
                    String extra = "学分 " + (c.credit == null ? "" : c.credit)
                            + " · " + (c.category == null ? "" : c.category);
                    items.add(row("course", c.id, c.code, c.name, null, null, c.subject, extra,
                            l -> l.courseId == c.id, dt, lessons));
                }
                break;
            }
            case "classroom": {
                for (Classroom c : catalog.classrooms()) {
                    if (schoolId != null && !Objects.equals(c.schoolId, schoolId)) continue;
                    if (!kw.isEmpty() && !match(c.name, c.code, kw)) continue;
                    String extra = "容量 " + (c.capacity == null ? "" : c.capacity)
                            + " · " + (c.roomType == null ? "" : c.roomType);
                    items.add(row("classroom", c.id, c.code, c.name, c.schoolId, null, null, extra,
                            l -> l.classroomId == c.id, dt, lessons));
                }
                break;
            }
            default:
                throw new ApiException(400, "BAD_TYPE", "不支持的对象类型：" + type);
        }
        return Resp.map("demoTime", Resp.ts(dt), "type", type, "total", items.size(), "items", items);
    }

    // ---------- 对象详情 ----------

    public Map<String, Object> detail(String type, long id) {
        LocalDateTime dt = demoClock.now();
        List<LessonView> lessons = business.allLessons();
        String code = null;
        String name = null;
        Long schoolId = null;
        List<Map<String, Object>> attributes = new ArrayList<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        Predicate<LessonView> filter = l -> false;
        long studentCount = 0;

        switch (type == null ? "" : type) {
            case "student": {
                Student s = catalog.student(id);
                if (s == null) throw notFound(type, id);
                Clazz clz = catalog.clazz(s.classId);
                code = s.code;
                name = s.name;
                schoolId = clz == null ? null : clz.schoolId;
                filter = l -> l.classId == s.classId;
                attr(attributes, "编号", s.code);
                attr(attributes, "性别", s.gender);
                attr(attributes, "学号", s.studentNo);
                attr(attributes, "入学日期", s.enrollDate == null ? "" : Resp.d(s.enrollDate));
                attr(attributes, "监护人电话", s.guardianPhone);
                relations.add(relation("class", s.classId, "所在班级"));
                relations.add(relation("school", schoolId, "所在学校"));
                if (clz != null && clz.headTeacherId != null) {
                    relations.add(relation("teacher", clz.headTeacherId, "班主任"));
                }
                for (Long tid : lessons.stream().filter(l -> l.classId == s.classId)
                        .map(l -> l.teacherId).distinct().toList()) {
                    relations.add(relation("teacher", tid, "当日任课教师"));
                }
                break;
            }
            case "teacher": {
                Teacher t = catalog.teacher(id);
                if (t == null) throw notFound(type, id);
                code = t.code;
                name = t.name;
                schoolId = t.schoolId;
                filter = l -> l.teacherId == t.id;
                attr(attributes, "编号", t.code);
                attr(attributes, "授课科目", t.subject);
                attr(attributes, "职称", t.title);
                attr(attributes, "联系电话", t.phone);
                attr(attributes, "状态", t.status);
                attr(attributes, "可代课", Boolean.TRUE.equals(t.substituteAvailable) ? "是" : "否");
                relations.add(relation("school", t.schoolId, "所属学校"));
                for (Long cid : lessons.stream().filter(l -> l.teacherId == t.id)
                        .map(l -> l.classId).distinct().toList()) {
                    relations.add(relation("class", cid, "任教班级"));
                }
                for (Long coid : lessons.stream().filter(l -> l.teacherId == t.id)
                        .map(l -> l.courseId).distinct().toList()) {
                    relations.add(relation("course", coid, "所授课程"));
                }
                break;
            }
            case "class": {
                Clazz c = catalog.clazz(id);
                if (c == null) throw notFound(type, id);
                code = c.code;
                name = c.name;
                schoolId = c.schoolId;
                filter = l -> l.classId == c.id;
                studentCount = catalog.countStudentsOfClass(id);
                attr(attributes, "编号", c.code);
                attr(attributes, "年级", c.grade);
                attr(attributes, "班主任", c.headTeacherId == null ? "" : nameOfTeacher(c.headTeacherId));
                attr(attributes, "教室", c.homeRoomId == null ? "" : nameOfClassroom(c.homeRoomId));
                attr(attributes, "学生数", studentCount);
                relations.add(relation("school", c.schoolId, "所属学校"));
                if (c.headTeacherId != null) {
                    relations.add(relation("teacher", c.headTeacherId, "班主任"));
                }
                for (Student st : catalog.studentsOfClass(id).stream().limit(8).toList()) {
                    relations.add(relation("student", st.id, "本班学生"));
                }
                for (Long tid : lessons.stream().filter(l -> l.classId == c.id)
                        .map(l -> l.teacherId).distinct().toList()) {
                    relations.add(relation("teacher", tid, "任课教师"));
                }
                break;
            }
            case "school": {
                School s = catalog.school(id);
                if (s == null) throw notFound(type, id);
                code = s.code;
                name = s.name;
                schoolId = s.id;
                filter = l -> l.schoolId == s.id;
                studentCount = catalog.countStudentsOfSchool(id);
                attr(attributes, "编号", s.code);
                attr(attributes, "地址", s.address);
                attr(attributes, "联系电话", s.phone);
                attr(attributes, "校训", s.slogan);
                for (Clazz c : catalog.classesOfSchool(id)) {
                    relations.add(relation("class", c.id, "下属班级"));
                }
                for (Teacher t : catalog.teachersOfSchool(id)) {
                    relations.add(relation("teacher", t.id, "教师"));
                }
                for (Classroom c : catalog.classroomsOfSchool(id)) {
                    relations.add(relation("classroom", c.id, "教室"));
                }
                break;
            }
            case "course": {
                Course c = catalog.course(id);
                if (c == null) throw notFound(type, id);
                code = c.code;
                name = c.name;
                filter = l -> l.courseId == c.id;
                attr(attributes, "编号", c.code);
                attr(attributes, "科目", c.subject);
                attr(attributes, "学分", c.credit);
                attr(attributes, "类别", c.category);
                List<LessonView> cl = lessons.stream().filter(l -> l.courseId == c.id).toList();
                for (Long cid : cl.stream().map(l -> l.classId).distinct().toList()) {
                    relations.add(relation("class", cid, "当天授课班级"));
                }
                for (Long tid : cl.stream().map(l -> l.teacherId).distinct().toList()) {
                    relations.add(relation("teacher", tid, "当天授课教师"));
                }
                break;
            }
            case "classroom": {
                Classroom c = catalog.classroom(id);
                if (c == null) throw notFound(type, id);
                code = c.code;
                name = c.name;
                schoolId = c.schoolId;
                filter = l -> l.classroomId == c.id;
                attr(attributes, "编号", c.code);
                attr(attributes, "所属学校", catalog.schoolName(c.schoolId));
                attr(attributes, "楼栋", c.building);
                attr(attributes, "容量", c.capacity);
                attr(attributes, "类型", c.roomType);
                relations.add(relation("school", c.schoolId, "所属学校"));
                for (Long cid : lessons.stream().filter(l -> l.classroomId == c.id)
                        .map(l -> l.classId).distinct().toList()) {
                    relations.add(relation("class", cid, "当天使用该教室的班级"));
                }
                break;
            }
            default:
                throw new ApiException(400, "BAD_TYPE", "不支持的对象类型：" + type);
        }

        List<LessonView> schedule = lessons.stream().filter(filter).toList();
        Set<Long> sessionIds = schedule.stream().map(l -> l.id).collect(Collectors.toSet());
        List<Map<String, Object>> recent = changeService.list(null, 50).stream()
                .filter(c -> sessionIds.contains(toLong(c.get("sessionId"))))
                .limit(5).toList();

        return Resp.map("demoTime", Resp.ts(dt), "type", type, "id", id,
                "code", code, "name", name,
                "school", schoolRef(schoolId),
                "attributes", attributes,
                "relations", relations.stream().filter(Objects::nonNull).toList(),
                "currentLesson", business.currentOf(dt, lessons, filter),
                "nextLesson", business.nextOf(dt, lessons, filter),
                "schedule", schedule,
                "recentChanges", recent,
                "studentCount", studentCount);
    }

    // ---------- 组装辅助 ----------

    private ObjectRow studentRow(Student s, Clazz clz, LocalDateTime dt, List<LessonView> lessons) {
        Long schoolId = clz == null ? null : clz.schoolId;
        String extra = (s.gender == null ? "" : s.gender) + " · 学号 " + (s.studentNo == null ? "" : s.studentNo);
        return row("student", s.id, s.code, s.name, schoolId, s.classId, null, extra,
                l -> l.classId == s.classId, dt, lessons);
    }

    private ObjectRow teacherRow(Teacher t, LocalDateTime dt, List<LessonView> lessons) {
        String extra = (t.title == null ? "" : t.title)
                + (Boolean.TRUE.equals(t.substituteAvailable) ? " · 可代课" : "");
        return row("teacher", t.id, t.code, t.name, t.schoolId, null, t.subject, extra,
                l -> l.teacherId == t.id, dt, lessons);
    }

    private ObjectRow row(String type, long id, String code, String name,
                          Long schoolId, Long classId, String subject, String extra,
                          Predicate<LessonView> filter, LocalDateTime dt, List<LessonView> lessons) {
        ObjectRow r = new ObjectRow();
        r.type = type;
        r.id = id;
        r.code = code;
        r.name = name;
        r.schoolId = schoolId;
        r.schoolName = catalog.schoolName(schoolId);
        r.classId = classId;
        Clazz clz = catalog.clazz(classId);
        r.className = clz == null ? null : clz.name;
        r.subject = subject;
        r.extra = extra == null ? "" : extra;
        r.currentLesson = business.currentOf(dt, lessons, filter);
        r.nextLesson = business.nextOf(dt, lessons, filter);
        return r;
    }

    private boolean match(String name, String code, String kw) {
        return (name != null && name.toLowerCase().contains(kw))
                || (code != null && code.toLowerCase().contains(kw));
    }

    private boolean teachesClass(long teacherId, long classId, List<LessonView> lessons) {
        return lessons.stream().anyMatch(l -> l.teacherId == teacherId && l.classId == classId);
    }

    private void attr(List<Map<String, Object>> attrs, String label, Object value) {
        attrs.add(Resp.map("label", label, "value", value == null ? "" : String.valueOf(value)));
    }

    private Map<String, Object> schoolRef(Long schoolId) {
        School s = catalog.school(schoolId);
        if (s == null) return null;
        return Resp.map("id", s.id, "code", s.code, "name", s.name, "type", "school");
    }

    private Map<String, Object> relation(String type, Long id, String role) {
        String code = "";
        String name = "";
        switch (type) {
            case "school": {
                School s = catalog.school(id);
                if (s != null) { code = s.code; name = s.name; }
                break;
            }
            case "class": {
                Clazz c = catalog.clazz(id);
                if (c != null) { code = c.code; name = c.name; }
                break;
            }
            case "teacher": {
                Teacher t = catalog.teacher(id);
                if (t != null) { code = t.code; name = t.name; }
                break;
            }
            case "student": {
                Student st = catalog.student(id);
                if (st != null) { code = st.code; name = st.name; }
                break;
            }
            case "course": {
                Course c = catalog.course(id);
                if (c != null) { code = c.code; name = c.name; }
                break;
            }
            case "classroom": {
                Classroom c = catalog.classroom(id);
                if (c != null) { code = c.code; name = c.name; }
                break;
            }
            default:
                break;
        }
        return Resp.map("type", type, "id", id, "code", code, "name", name, "role", role);
    }

    private String nameOfTeacher(Long id) {
        Teacher t = catalog.teacher(id);
        return t == null ? "" : t.name;
    }

    private String nameOfClassroom(Long id) {
        Classroom c = catalog.classroom(id);
        return c == null ? "" : c.name;
    }

    private ApiException notFound(String type, long id) {
        return new ApiException(404, "NOT_FOUND", "对象不存在：" + type + "/" + id);
    }

    private long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return -1L;
        }
    }
}
