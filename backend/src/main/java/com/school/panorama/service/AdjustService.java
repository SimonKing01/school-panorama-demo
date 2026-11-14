package com.school.panorama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.panorama.common.ApiException;
import com.school.panorama.common.Resp;
import com.school.panorama.dto.AdjustBody;
import com.school.panorama.dto.LessonView;
import com.school.panorama.entity.Classroom;
import com.school.panorama.entity.Clazz;
import com.school.panorama.entity.Teacher;
import com.school.panorama.mapper.ChangeRecordMapper;
import com.school.panorama.mapper.LessonSessionMapper;
import com.school.panorama.mapper.SystemRevisionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 调课校验（preview 与 adjust 共用）与单事务写入。
 * 校验顺序严格按契约：原因 / 时间 / 教师学校 / 教师科目 / 教室学校 / 教师冲突 / 班级冲突 / 教室冲突。
 */
@Service
public class AdjustService {

    @Autowired private LessonSessionMapper lessonSessionMapper;
    @Autowired private ChangeRecordMapper changeRecordMapper;
    @Autowired private SystemRevisionMapper systemRevisionMapper;
    @Autowired private BusinessQueryService business;
    @Autowired private CatalogService catalog;
    @Autowired private DemoClockService demoClock;
    @Autowired private ChangeService changeService;
    @Autowired private ObjectMapper objectMapper;

    public Map<String, Object> preview(long id, AdjustBody body) {
        return validate(id, body).view;
    }

    @Transactional
    public Map<String, Object> adjust(long id, AdjustBody body) {
        // 事务开始即串行化，避免并发调课漏判冲突
        lessonSessionMapper.advisoryLock();
        Eval e = validate(id, body);
        if (!e.conflicts.isEmpty()) {
            // 抛异常回滚，不产生任何写入，revision 不变；body 为 preview 结构并附 message/code 便于前端统一展示
            Map<String, Object> fail = new java.util.LinkedHashMap<>(e.view);
            fail.put("message", "调课校验未通过");
            fail.put("code", "VALIDATION_FAILED");
            throw new ApiException(400, "VALIDATION_FAILED", "调课校验未通过", e.conflicts, fail);
        }
        lessonSessionMapper.applyAdjust(Resp.map("id", id,
                "teacherId", e.teacherId, "classroomId", e.classroomId,
                "lessonDate", e.lessonDate, "startTime", e.startTime, "endTime", e.endTime));
        changeRecordMapper.insertChange(Resp.map(
                "sessionId", id, "sessionCode", e.lesson.code, "schoolId", e.lesson.schoolId,
                "changeType", "调课", "reason", body.reason, "operator", "演示管理员",
                "occurredAt", demoClock.demoTimeString(),
                "beforeJson", json(e.before), "afterJson", json(e.after),
                "diffJson", json(e.diff), "affectedJson", json(e.impact),
                "affectedStudentCount", e.impact.get("studentCount")));
        systemRevisionMapper.bump();

        LessonView newLesson = business.allLessons().stream()
                .filter(l -> l.id == id).findFirst().orElse(e.lesson);
        Map<String, Object> change = changeService.detail(changeRecordMapper.maxId());
        return Resp.map("demoTime", demoClock.demoTimeString(),
                "lesson", newLesson, "change", change,
                "impact", e.impact, "diff", e.diff,
                "revision", systemRevisionMapper.revision());
    }

    private Eval validate(long id, AdjustBody body) {
        AdjustBody b = body == null ? new AdjustBody() : body;
        List<LessonView> lessons = business.allLessons();
        LessonView lesson = lessons.stream().filter(l -> l.id == id).findFirst()
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "授课安排不存在"));

        Eval e = new Eval();
        e.lesson = lesson;
        e.teacherId = b.teacherId != null ? b.teacherId : lesson.teacherId;
        e.classroomId = b.classroomId != null ? b.classroomId : lesson.classroomId;
        e.lessonDate = notBlank(b.lessonDate) ? b.lessonDate : lesson.lessonDate;
        e.startTime = notBlank(b.startTime) ? b.startTime : lesson.startTime;
        e.endTime = notBlank(b.endTime) ? b.endTime : lesson.endTime;
        Teacher nt = catalog.teacher(e.teacherId);
        Classroom nc = catalog.classroom(e.classroomId);
        Clazz clz = catalog.clazz(lesson.classId);
        Long schoolId = clz == null ? lesson.schoolId : clz.schoolId;

        // 1 原因必填
        if (b.reason == null || b.reason.isBlank()) {
            conflict(e, "REASON_REQUIRED", "必须填写调整原因");
        }
        // 2 时间合法
        boolean timeOk = validTime(e);
        if (!timeOk) {
            conflict(e, "TIME_INVALID", "结束时间必须晚于开始时间，且时间格式应为 HH:mm");
        }
        // 3 教师所属学校 = 班级所属学校
        if (nt == null) {
            conflict(e, "TEACHER_NOT_FOUND", "所选教师不存在");
        } else if (!Objects.equals(nt.schoolId, schoolId)) {
            conflict(e, "TEACHER_SCHOOL_MISMATCH", "教师" + nt.name + "属于"
                    + catalog.schoolName(nt.schoolId) + "，与授课班级所属学校"
                    + catalog.schoolName(schoolId) + "不一致");
        }
        // 4 教师授课科目 = 课程科目
        if (nt != null && !Objects.equals(nt.subject, lesson.subject)) {
            conflict(e, "TEACHER_SUBJECT_MISMATCH", "教师" + nt.name + "授课科目为"
                    + nt.subject + "，与课程《" + lesson.courseName + "》科目" + lesson.subject + "不一致");
        }
        // 5 教室所属学校
        if (nc == null) {
            conflict(e, "CLASSROOM_NOT_FOUND", "所选教室不存在");
        } else if (!Objects.equals(nc.schoolId, schoolId)) {
            conflict(e, "CLASSROOM_SCHOOL_MISMATCH", "教室" + nc.name + "不属于授课班级所属学校"
                    + catalog.schoolName(schoolId));
        }
        // 6~8 时间冲突（同一日期、重叠公式，排除本次课自身）
        if (timeOk) {
            LocalDateTime ns = business.startAt(e.lessonDate, e.startTime);
            LocalDateTime ne = business.endAt(e.lessonDate, e.endTime);
            for (LessonView o : lessons) {
                if (o.id == id) continue;
                if (!o.lessonDate.equals(e.lessonDate)) continue;
                LocalDateTime os = business.startAt(o);
                LocalDateTime oe = business.endAt(o);
                if (!(ns.isBefore(oe) && ne.isAfter(os))) continue;
                if (o.teacherId == e.teacherId) {
                    conflict(e, "TEACHER_CONFLICT", "教师" + teacherName(e, nt) + " "
                            + o.startTime + "-" + o.endTime + " 已在" + o.className + "授课");
                }
                if (o.classId == lesson.classId) {
                    conflict(e, "CLASS_CONFLICT", "班级" + o.className + " "
                            + o.startTime + "-" + o.endTime + " 已有《" + o.courseName + "》课程");
                }
                if (o.classroomId == e.classroomId) {
                    conflict(e, "CLASSROOM_CONFLICT", "教室" + classroomName(e, nc) + " "
                            + o.startTime + "-" + o.endTime + " 已被" + o.className + "占用");
                }
            }
        }

        e.before = Resp.map("teacherId", lesson.teacherId, "teacherName", lesson.teacherName,
                "classroomId", lesson.classroomId, "classroomName", lesson.classroomName,
                "className", lesson.className, "courseName", lesson.courseName,
                "lessonDate", lesson.lessonDate, "startTime", lesson.startTime, "endTime", lesson.endTime);
        String newTeacherName = teacherName(e, nt);
        String newClassroomName = classroomName(e, nc);
        e.after = Resp.map("teacherId", e.teacherId, "teacherName", newTeacherName,
                "classroomId", e.classroomId, "classroomName", newClassroomName,
                "className", lesson.className, "courseName", lesson.courseName,
                "lessonDate", e.lessonDate, "startTime", e.startTime, "endTime", e.endTime);

        e.diff = new ArrayList<>();
        addDiff(e.diff, "teacherName", "授课教师", lesson.teacherName, newTeacherName);
        addDiff(e.diff, "lessonDate", "日期", lesson.lessonDate, e.lessonDate);
        addDiff(e.diff, "startTime", "开始时间", lesson.startTime, e.startTime);
        addDiff(e.diff, "endTime", "结束时间", lesson.endTime, e.endTime);
        addDiff(e.diff, "classroomName", "教室", lesson.classroomName, newClassroomName);

        List<Long> studentIds = catalog.studentIdsOfClass(lesson.classId);
        e.impact = Resp.map("studentCount", studentIds.size(), "studentIds", studentIds,
                "classIds", List.of(lesson.classId), "classNames", List.of(lesson.className),
                "teacherIds", distinctLong(Arrays.asList(lesson.teacherId, e.teacherId)),
                "teacherNames", distinctStr(Arrays.asList(lesson.teacherName, newTeacherName)),
                "classroomIds", distinctLong(Arrays.asList(lesson.classroomId, e.classroomId)),
                "classroomNames", distinctStr(Arrays.asList(lesson.classroomName, newClassroomName)));

        e.view = Resp.map("demoTime", demoClock.demoTimeString(),
                "valid", e.conflicts.isEmpty(), "conflicts", e.conflicts,
                "before", e.before, "after", e.after, "diff", e.diff, "impact", e.impact);
        return e;
    }

    private void conflict(Eval e, String type, String message) {
        e.conflicts.add(Resp.map("type", type, "message", message));
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private boolean validTime(Eval e) {
        try {
            LocalTime s = LocalTime.parse(e.startTime);
            LocalTime en = LocalTime.parse(e.endTime);
            return en.isAfter(s);
        } catch (Exception ex) {
            return false;
        }
    }

    private String teacherName(Eval e, Teacher nt) {
        return nt != null ? nt.name
                : (e.teacherId == e.lesson.teacherId ? e.lesson.teacherName : "");
    }

    private String classroomName(Eval e, Classroom nc) {
        return nc != null ? nc.name
                : (e.classroomId == e.lesson.classroomId ? e.lesson.classroomName : "");
    }

    private void addDiff(List<Map<String, Object>> diff, String field, String label, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            diff.add(Resp.map("field", field, "label", label, "before", before, "after", after));
        }
    }

    private List<Long> distinctLong(List<Long> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    private List<String> distinctStr(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception ex) {
            throw new RuntimeException("JSON 序列化失败", ex);
        }
    }

    private static class Eval {
        LessonView lesson;
        Long teacherId;
        Long classroomId;
        String lessonDate;
        String startTime;
        String endTime;
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, Object> before;
        Map<String, Object> after;
        Map<String, Object> impact;
        Map<String, Object> view;
        List<Map<String, Object>> diff;
    }
}
