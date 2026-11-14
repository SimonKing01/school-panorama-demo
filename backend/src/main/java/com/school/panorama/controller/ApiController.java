package com.school.panorama.controller;

import com.school.panorama.common.Resp;
import com.school.panorama.dto.AdjustBody;
import com.school.panorama.dto.ClockRequest;
import com.school.panorama.service.AdjustService;
import com.school.panorama.service.ChangeService;
import com.school.panorama.service.DemoClockService;
import com.school.panorama.service.ObjectViewService;
import com.school.panorama.service.OverviewService;
import com.school.panorama.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 契约 1~10 全部接口，统一 /api 前缀；可选筛选参数用字符串接收以容忍空串。 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired private DemoClockService demoClock;
    @Autowired private ObjectViewService objectView;
    @Autowired private OverviewService overviewService;
    @Autowired private ScheduleService scheduleService;
    @Autowired private AdjustService adjustService;
    @Autowired private ChangeService changeService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return demoClock.healthView();
    }

    @GetMapping("/meta/options")
    public Map<String, Object> metaOptions() {
        return objectView.metaOptions();
    }

    @GetMapping("/demo/clock")
    public Map<String, Object> clock() {
        return demoClock.clockView();
    }

    @PostMapping("/demo/clock")
    public Map<String, Object> updateClock(@RequestBody(required = false) ClockRequest req) {
        return demoClock.updateClock(req);
    }

    @PostMapping("/demo/clock/sync-today")
    public Map<String, Object> syncToday() {
        return demoClock.syncToday();
    }

    @PostMapping("/demo/reset")
    public Map<String, Object> reset() {
        return demoClock.reset();
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return overviewService.overview();
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "all") String type,
            @RequestParam(required = false) String schoolId,
            @RequestParam(required = false) String classId) {
        return objectView.search(q, type, asLong(schoolId), asLong(classId));
    }

    @GetMapping("/objects")
    public Map<String, Object> objects(
            @RequestParam(required = false, defaultValue = "student") String type,
            @RequestParam(required = false) String schoolId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false, defaultValue = "") String q) {
        return objectView.objects(type, asLong(schoolId), asLong(classId), q);
    }

    @GetMapping("/objects/{type}/{id}")
    public Map<String, Object> objectDetail(@PathVariable String type, @PathVariable long id) {
        return objectView.detail(type, id);
    }

    @GetMapping("/schedule")
    public Map<String, Object> schedule(
            @RequestParam(required = false) String schoolId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String teacherId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String status) {
        return scheduleService.list(asLong(schoolId), asLong(classId), asLong(teacherId), date, status);
    }

    @GetMapping("/schedule/{id}")
    public Map<String, Object> scheduleDetail(@PathVariable long id) {
        return scheduleService.detail(id);
    }

    @PostMapping("/schedule/{id}/preview")
    public Map<String, Object> preview(@PathVariable long id,
                                       @RequestBody(required = false) AdjustBody body) {
        return adjustService.preview(id, body);
    }

    @PostMapping("/schedule/{id}/adjust")
    public Map<String, Object> adjust(@PathVariable long id,
                                      @RequestBody(required = false) AdjustBody body) {
        return adjustService.adjust(id, body);
    }

    @GetMapping("/changes")
    public Map<String, Object> changes(
            @RequestParam(required = false) String schoolId,
            @RequestParam(required = false) String limit) {
        Long sid = asLong(schoolId);
        int lim = asInt(limit, 50);
        return Resp.map("demoTime", demoClock.demoTimeString(),
                "total", changeService.count(sid),
                "items", changeService.list(sid, lim));
    }

    @GetMapping("/changes/{id}")
    public Map<String, Object> changeDetail(@PathVariable long id) {
        return changeService.detail(id);
    }

    @GetMapping("/revision")
    public Map<String, Object> revision() {
        return demoClock.revisionView();
    }

    private Long asLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private int asInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
