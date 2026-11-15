package com.school.ontology.adapter.school.resolver;

import com.school.ontology.common.Resp;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.spi.DynamicResolver;
import com.school.ontology.core.spi.DynamicResult;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.core.spi.ResolveContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「当前课程 / 下一节课」动态计算（C2/C3/F6）。
 *
 * <p>唯一时间源是 {@code ctx.queryTime()}（演示业务时间），结果不落库、不缓存，
 * 因此即使没有任何数据写入，时间跨过上课边界也会自动变化。返回带可读计算依据
 * basis，界面直接展示，不在多页面重复实现。</p>
 *
 * <p>候选授课安排通过只读投影关系做两层遍历得到，不写死「学生/班级/教师」分支：
 * 学生→班级→课表、班级→课表、教师←课表、授课安排→班级→课表均自然成立。</p>
 */
@Component
public class SchoolLessonResolver implements DynamicResolver {

    private static final int MAX_NODES = 200;

    @Override
    public String key() {
        return "currentLesson";
    }

    @Override
    public List<String> aliases() {
        return List.of("nextLesson");
    }

    @Override
    public DynamicResult resolve(ResolveContext ctx) {
        LocalDateTime queryTime = ctx.queryTime() == null ? LocalDateTime.now() : ctx.queryTime();
        Set<String> sessionGids = collectLessonSessions(ctx);
        List<Candidate> candidates = new ArrayList<>();
        for (String gid : sessionGids) {
            ProjectionRow row = ctx.query().object(gid);
            if (row == null || row.deleted()) {
                continue;
            }
            Candidate candidate = toCandidate(gid, row.attributes());
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparing(Candidate::start));

        boolean next = "nextLesson".equals(ctx.resolverKey());
        Candidate selected = null;
        for (Candidate candidate : candidates) {
            if (next) {
                if (candidate.start().isAfter(queryTime)) {
                    selected = candidate;
                    break;
                }
            } else if (!candidate.start().isAfter(queryTime)
                    && candidate.end().isAfter(queryTime)) {
                // 开始 ≤ 演示时间 < 结束，即为「当前课程」。
                selected = candidate;
                break;
            }
        }

        List<String> inputs = new ArrayList<>(sessionGids);
        String timeText = Resp.ts(queryTime);
        String basis = next
                ? "依据演示时间 " + timeText + "，从对象 " + ctx.globalId()
                + " 的两层关系取出 " + sessionGids.size() + " 条授课安排，"
                + "在开始时间晚于演示时间的安排中取时间最早的一节；无满足条件的安排则显示空状态。"
                : "依据演示时间 " + timeText + "，从对象 " + ctx.globalId()
                + " 的两层关系取出 " + sessionGids.size() + " 条授课安排，"
                + "取满足 开始≤演示时间<结束 的最早一节；无满足条件的安排则显示空状态。";
        if (selected == null) {
            return DynamicResult.empty(ctx.resolverKey(), basis, inputs, queryTime);
        }
        return new DynamicResult(ctx.resolverKey(), selected.view(), basis, inputs, queryTime);
    }

    /** 两层关系遍历收集授课安排，纯数据驱动，不按业务类型分派。 */
    private Set<String> collectLessonSessions(ResolveContext ctx) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(ctx.globalId());
        visited.add(ctx.globalId());
        for (int depth = 0; depth < 2 && !queue.isEmpty() && visited.size() < MAX_NODES; depth++) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                if (current == null) {
                    continue;
                }
                for (RelationInstance relation : ctx.query().relationsFrom(current, true)) {
                    if (visited.add(relation.toGlobalId())) {
                        queue.add(relation.toGlobalId());
                    }
                }
                for (RelationInstance relation : ctx.query().relationsTo(current, true)) {
                    if (visited.add(relation.fromGlobalId())) {
                        queue.add(relation.fromGlobalId());
                    }
                }
            }
        }
        Set<String> result = new LinkedHashSet<>();
        for (String gid : visited) {
            if ("lessonSession".equals(objectTypeOf(gid))) {
                result.add(gid);
            }
        }
        return result;
    }

    private static String objectTypeOf(String globalId) {
        String[] parts = globalId == null ? new String[0] : globalId.split(":");
        return parts.length > 2 ? parts[2] : "";
    }

    private static Candidate toCandidate(String gid, Map<String, Object> attributes) {
        try {
            LocalDate date = LocalDate.parse(String.valueOf(attributes.get("lessonDate")));
            LocalTime startTime = LocalTime.parse(String.valueOf(attributes.get("startTime")));
            LocalTime endTime = LocalTime.parse(String.valueOf(attributes.get("endTime")));
            Map<String, Object> view = new LinkedHashMap<>();
            for (String key : List.of("code", "courseName", "className", "teacherName",
                    "classroomName", "lessonDate", "startTime", "endTime", "periodName")) {
                view.put(key, attributes.get(key));
            }
            view.put("globalId", gid);
            return new Candidate(LocalDateTime.of(date, startTime),
                    LocalDateTime.of(date, endTime), view);
        } catch (Exception e) {
            return null;
        }
    }

    private record Candidate(LocalDateTime start, LocalDateTime end, Map<String, Object> view) {
    }
}
