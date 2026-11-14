package com.school.panorama.service;

import com.school.panorama.dto.LessonView;
import com.school.panorama.mapper.LessonSessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Predicate;

/**
 * 共享业务查询：所有视角复用同一份 lesson_sessions JOIN 结果。
 * status 与 current/next 都基于统一演示时间计算，禁止各视角缓存业务事实。
 */
@Service
public class BusinessQueryService {

    @Autowired private LessonSessionMapper lessonSessionMapper;
    @Autowired private DemoClockService demoClock;

    public List<LessonView> allLessons() {
        LocalDateTime dt = demoClock.now();
        List<LessonView> list = lessonSessionMapper.selectAllLessonViews();
        for (LessonView l : list) {
            l.status = status(l, dt);
        }
        return list;
    }

    public LocalDateTime startAt(LessonView l) {
        return LocalDateTime.of(LocalDate.parse(l.lessonDate), LocalTime.parse(l.startTime));
    }

    public LocalDateTime endAt(LessonView l) {
        return LocalDateTime.of(LocalDate.parse(l.lessonDate), LocalTime.parse(l.endTime));
    }

    public LocalDateTime startAt(String date, String time) {
        return LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time));
    }

    public LocalDateTime endAt(String date, String time) {
        return LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time));
    }

    public String status(LessonView l, LocalDateTime dt) {
        LocalDateTime s = startAt(l);
        LocalDateTime e = endAt(l);
        if (!dt.isBefore(s) && dt.isBefore(e)) {
            return "正在上课";
        }
        if (s.isAfter(dt)) {
            return "即将开始";
        }
        return "已结束";
    }

    /** 当前正在上的一次课：startAt <= demoTime < endAt，取最早开始的一个。 */
    public LessonView currentOf(LocalDateTime dt, List<LessonView> lessons, Predicate<LessonView> filter) {
        LessonView best = null;
        for (LessonView l : lessons) {
            if (filter.test(l) && !dt.isBefore(startAt(l)) && dt.isBefore(endAt(l))) {
                if (best == null || startAt(l).isBefore(startAt(best))) {
                    best = l;
                }
            }
        }
        return best;
    }

    /** 下一节即将开始的课：startAt > demoTime 中开始时间最早的一次。 */
    public LessonView nextOf(LocalDateTime dt, List<LessonView> lessons, Predicate<LessonView> filter) {
        LessonView best = null;
        for (LessonView l : lessons) {
            if (filter.test(l) && startAt(l).isAfter(dt)) {
                if (best == null || startAt(l).isBefore(startAt(best))) {
                    best = l;
                }
            }
        }
        return best;
    }
}
