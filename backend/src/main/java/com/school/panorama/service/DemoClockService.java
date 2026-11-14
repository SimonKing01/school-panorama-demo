package com.school.panorama.service;

import com.school.panorama.common.Resp;
import com.school.panorama.dto.ClockRequest;
import com.school.panorama.entity.DemoState;
import com.school.panorama.mapper.ChangeRecordMapper;
import com.school.panorama.mapper.DemoStateMapper;
import com.school.panorama.mapper.LessonSessionMapper;
import com.school.panorama.mapper.LessonSessionSeedMapper;
import com.school.panorama.mapper.SystemRevisionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 统一演示时钟、版本号，以及同步到今天 / 恢复演示数据两个单事务操作。 */
@Service
public class DemoClockService {

    @Autowired private DemoStateMapper demoStateMapper;
    @Autowired private SystemRevisionMapper systemRevisionMapper;
    @Autowired private LessonSessionMapper lessonSessionMapper;
    @Autowired private LessonSessionSeedMapper seedMapper;
    @Autowired private ChangeRecordMapper changeRecordMapper;

    private DemoState state() {
        DemoState ds = demoStateMapper.selectById(1);
        if (ds == null || ds.demoAt == null) {
            DemoState d = new DemoState();
            d.demoAt = LocalDate.now().atTime(10, 15);
            d.running = false;
            d.anchorRealAt = LocalDateTime.now();
            return d;
        }
        return ds;
    }

    /** running=false 固定在 demo_at；running=true 按真实时钟从锚点推进。 */
    public LocalDateTime now() {
        DemoState ds = state();
        if (Boolean.TRUE.equals(ds.running) && ds.anchorRealAt != null) {
            return ds.demoAt.plus(Duration.between(ds.anchorRealAt, LocalDateTime.now()));
        }
        return ds.demoAt;
    }

    public String demoTimeString() {
        return Resp.ts(now());
    }

    public boolean running() {
        return Boolean.TRUE.equals(state().running);
    }

    public long revision() {
        return systemRevisionMapper.revision();
    }

    public void bumpRevision() {
        systemRevisionMapper.bump();
    }

    public Map<String, Object> healthView() {
        String db = "UP";
        String demoTime;
        long rev;
        try {
            systemRevisionMapper.ping();
            demoTime = demoTimeString();
            rev = revision();
        } catch (Exception e) {
            db = "DOWN";
            demoTime = Resp.ts(LocalDateTime.now());
            rev = 0L;
        }
        return Resp.map("status", "UP", "db", db, "demoTime", demoTime, "revision", rev);
    }

    public Map<String, Object> revisionView() {
        return Resp.map("revision", revision(), "demoTime", demoTimeString(), "running", running());
    }

    public Map<String, Object> clockView() {
        LocalDateTime dt = now();
        LocalDate ld = dt.toLocalDate();
        List<Map<String, Object>> presets = new ArrayList<>();
        presets.add(preset(ld, "上课前 07:30", 7, 30));
        presets.add(preset(ld, "第三节课中 10:15", 10, 15));
        presets.add(preset(ld, "第三节课结束 10:45", 10, 45));
        presets.add(preset(ld, "全部课程结束 17:00", 17, 0));
        return Resp.map("demoTime", Resp.ts(dt), "running", running(),
                "lessonDate", Resp.d(ld), "lessonEndTime", lessonSessionMapper.maxEndTime(),
                "presets", presets);
    }

    private Map<String, Object> preset(LocalDate ld, String label, int h, int m) {
        return Resp.map("label", label, "demoTime", Resp.ts(ld.atTime(h, m)));
    }

    /** demoTime 优先；否则 minuteOffset 在当前演示时间上偏移；running 随真实时间推进。 */
    public Map<String, Object> updateClock(ClockRequest req) {
        LocalDateTime realNow = LocalDateTime.now();
        LocalDateTime current = now();
        LocalDateTime newDemoAt = current;
        if (req != null && req.demoTime != null && !req.demoTime.isBlank()) {
            newDemoAt = Resp.parseTs(req.demoTime);
        } else if (req != null && req.minuteOffset != null) {
            newDemoAt = current.plusMinutes(req.minuteOffset);
        }
        boolean newRunning = (req != null && req.running != null) ? req.running : running();
        demoStateMapper.updateState(Resp.map("demoAt", newDemoAt, "running", newRunning, "anchorRealAt", realNow));
        bumpRevision();
        return clockView();
    }

    /** 把所有授课安排日期、演示时钟、变更记录时间整体平移到今天，演示时间固定为今天 10:15。 */
    @Transactional
    public Map<String, Object> syncToday() {
        LocalDate seedDate = seedMapper.minLessonDate();
        LocalDate today = LocalDate.now();
        int delta = (int) ChronoUnit.DAYS.between(seedDate == null ? today : seedDate, today);
        if (delta != 0) {
            lessonSessionMapper.shiftDates(delta);
            seedMapper.shiftDates(delta);
            changeRecordMapper.shiftTimes(delta);
            changeRecordMapper.shiftJsonbLessonDate(today.toString());
        }
        demoStateMapper.updateState(Resp.map("demoAt", today.atTime(10, 15),
                "running", false, "anchorRealAt", LocalDateTime.now()));
        bumpRevision();
        return clockView();
    }

    /** 恢复课表初始快照、清掉演示期新增变更、把预置记录时间归位，演示时间回到今天 10:15。 */
    @Transactional
    public Map<String, Object> reset() {
        lessonSessionMapper.resetFromSeed();
        seedMapper.alignToday();
        changeRecordMapper.deleteNonSeed();
        changeRecordMapper.resetSeedTimes();
        demoStateMapper.updateState(Resp.map("demoAt", LocalDate.now().atTime(10, 15),
                "running", false, "anchorRealAt", LocalDateTime.now()));
        bumpRevision();
        return clockView();
    }
}
