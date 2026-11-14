package com.school.panorama.service;

import com.school.panorama.common.Resp;
import com.school.panorama.dto.LessonView;
import com.school.panorama.entity.School;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 全局总览：统计与列表全部复用共享业务查询，不另拼业务事实。 */
@Service
public class OverviewService {

    @Autowired private CatalogService catalog;
    @Autowired private BusinessQueryService business;
    @Autowired private ChangeService changeService;
    @Autowired private DemoClockService demoClock;

    public Map<String, Object> overview() {
        LocalDateTime dt = demoClock.now();
        List<LessonView> lessons = business.allLessons();
        String today = Resp.d(dt.toLocalDate());
        List<LessonView> todayLessons = lessons.stream()
                .filter(l -> today.equals(l.lessonDate)).toList();
        List<LessonView> current = lessons.stream()
                .filter(l -> "正在上课".equals(l.status)).toList();

        Map<String, Object> counts = Resp.map(
                "schools", catalog.schools().size(),
                "teachers", catalog.teachers().size(),
                "classes", catalog.classes().size(),
                "students", catalog.students().size(),
                "courses", catalog.courses().size(),
                "classrooms", catalog.classrooms().size(),
                "sessionsToday", todayLessons.size(),
                "changes", changeService.count(null),
                "currentLessons", current.size());

        List<Map<String, Object>> perSchool = new ArrayList<>();
        for (School s : catalog.schools()) {
            long sessions = todayLessons.stream().filter(l -> l.schoolId == s.id).count();
            long cur = current.stream().filter(l -> l.schoolId == s.id).count();
            perSchool.add(Resp.map("schoolId", s.id, "schoolName", s.name,
                    "classCount", catalog.classesOfSchool(s.id).size(),
                    "teacherCount", catalog.teachersOfSchool(s.id).size(),
                    "studentCount", catalog.countStudentsOfSchool(s.id),
                    "sessionsToday", sessions, "currentLessonCount", cur));
        }

        return Resp.map("demoTime", Resp.ts(dt), "counts", counts, "perSchool", perSchool,
                "currentLessons", current, "todayLessons", todayLessons,
                "recentChanges", changeService.recent(5));
    }
}
