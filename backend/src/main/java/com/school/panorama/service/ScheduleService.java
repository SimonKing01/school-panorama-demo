package com.school.panorama.service;

import com.school.panorama.common.ApiException;
import com.school.panorama.common.Resp;
import com.school.panorama.dto.LessonView;
import com.school.panorama.entity.Classroom;
import com.school.panorama.entity.Teacher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 课表管理：列表、详情（调整前快照 + 候选教师/教室/空闲时段）。 */
@Service
public class ScheduleService {

    @Autowired private BusinessQueryService business;
    @Autowired private CatalogService catalog;
    @Autowired private DemoClockService demoClock;

    public Map<String, Object> list(Long schoolId, Long classId, Long teacherId, String date, String status) {
        LocalDateTime dt = demoClock.now();
        List<LessonView> items = business.allLessons().stream()
                .filter(l -> schoolId == null || l.schoolId == schoolId)
                .filter(l -> classId == null || l.classId == classId)
                .filter(l -> teacherId == null || l.teacherId == teacherId)
                .filter(l -> date == null || date.isEmpty() || date.equals(l.lessonDate))
                .filter(l -> status == null || status.isEmpty() || status.equals(l.status))
                .toList();
        long ongoing = items.stream().filter(l -> "正在上课".equals(l.status)).count();
        long upcoming = items.stream().filter(l -> "即将开始".equals(l.status)).count();
        long finished = items.stream().filter(l -> "已结束".equals(l.status)).count();
        Map<String, Object> stats = Resp.map("total", items.size(),
                "ongoing", ongoing, "upcoming", upcoming, "finished", finished);
        return Resp.map("demoTime", Resp.ts(dt), "total", items.size(), "items", items, "stats", stats);
    }

    public Map<String, Object> detail(long id) {
        LocalDateTime dt = demoClock.now();
        List<LessonView> lessons = business.allLessons();
        LessonView lesson = lessons.stream().filter(l -> l.id == id).findFirst()
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "授课安排不存在"));

        List<Map<String, Object>> teachers = new ArrayList<>();
        for (Teacher t : catalog.teachers()) {
            if (!Objects.equals(t.schoolId, lesson.schoolId)) continue;
            if (!Objects.equals(t.subject, lesson.subject) && t.id != lesson.teacherId) continue;
            teachers.add(Resp.map("id", t.id, "code", t.code, "name", t.name,
                    "schoolName", catalog.schoolName(t.schoolId), "subject", t.subject,
                    "substituteAvailable", Boolean.TRUE.equals(t.substituteAvailable),
                    "busy", busyTeacher(t.id, lesson, lessons)));
        }
        List<Map<String, Object>> classrooms = new ArrayList<>();
        for (Classroom c : catalog.classrooms()) {
            if (!Objects.equals(c.schoolId, lesson.schoolId)) continue;
            classrooms.add(Resp.map("id", c.id, "code", c.code, "name", c.name,
                    "schoolName", catalog.schoolName(c.schoolId), "capacity", c.capacity,
                    "busy", busyClassroom(c.id, lesson, lessons)));
        }
        List<Map<String, Object>> freeSlots = List.of(
                Resp.map("label", "第七节 15:30-16:15", "startTime", "15:30", "endTime", "16:15"),
                Resp.map("label", "第八节 16:20-17:05", "startTime", "16:20", "endTime", "17:05"));
        Map<String, Object> candidates = Resp.map("teachers", teachers,
                "classrooms", classrooms, "freeSlots", freeSlots);
        return Resp.map("demoTime", Resp.ts(dt), "lesson", lesson,
                "before", snapshot(lesson), "candidates", candidates);
    }

    /** 调整前/后统一快照，preview、schedule 详情与 change_records 复用。 */
    public Map<String, Object> snapshot(LessonView lesson) {
        return Resp.map("teacherId", lesson.teacherId, "teacherName", lesson.teacherName,
                "classroomId", lesson.classroomId, "classroomName", lesson.classroomName,
                "className", lesson.className, "courseName", lesson.courseName,
                "lessonDate", lesson.lessonDate, "startTime", lesson.startTime, "endTime", lesson.endTime);
    }

    /** busy：候选在本次课当前时段是否已被其他安排占用（排除本次课自身）。 */
    private boolean busyTeacher(long tid, LessonView lesson, List<LessonView> lessons) {
        LocalDateTime ns = business.startAt(lesson);
        LocalDateTime ne = business.endAt(lesson);
        return lessons.stream().anyMatch(o -> o.id != lesson.id && o.teacherId == tid
                && o.lessonDate.equals(lesson.lessonDate)
                && ns.isBefore(business.endAt(o)) && ne.isAfter(business.startAt(o)));
    }

    private boolean busyClassroom(long cid, LessonView lesson, List<LessonView> lessons) {
        LocalDateTime ns = business.startAt(lesson);
        LocalDateTime ne = business.endAt(lesson);
        return lessons.stream().anyMatch(o -> o.id != lesson.id && o.classroomId == cid
                && o.lessonDate.equals(lesson.lessonDate)
                && ns.isBefore(business.endAt(o)) && ne.isAfter(business.startAt(o)));
    }
}
