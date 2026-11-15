package com.school.ontology.adapter.school.provider;

import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.spi.DerivedPropertyProvider;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 教室使用量派生属性（批次④ 接入 classroom 类型时新增）。
 *
 * <p>按派生属性名（{@link DerivedPropertyDef#name}）分派两个指标，学校领域规则留在适配层，
 * 通用内核不认识「教室 / 排课」概念，因此 core 包无需任何改动：</p>
 *
 * <ul>
 *   <li>{@code sessionCount}：该教室在 lesson_sessions 中的排课条数（累计使用强度）；</li>
 *   <li>{@code usedPeriods}：该教室占用过的不同节次数（period_name 去重计数）。</li>
 * </ul>
 *
 * <p>两者都只依赖 classrooms ↔ lesson_sessions 的静态关联，不含「今天 / 当前时段」这类可变
 * 时间语义——派生属性会随投影落库，若混入可变时间，重建与首次导入会算出不同结果，
 * 违反「两类时间分离」硬约束。需要「此刻正在上什么课」请用动态 Resolver（按查询时间实时算、不落库）。</p>
 *
 * <p>实现只走 {@link com.school.ontology.core.spi.LookupResolver} 的只读能力（SELECT），
 * 连接已由适配层置为 read-only，不写业务库。</p>
 */
@Component
public class ClassroomUsageProvider implements DerivedPropertyProvider {

    /** 派生属性名 → 指标种类；新增指标只在此表加一行，不引入分支语句。 */
    private static final String SESSION_COUNT = "sessionCount";
    private static final String USED_PERIODS = "usedPeriods";

    /** 教室使用情况的来源表与关联列（来源为只读业务库）。 */
    private static final String SESSION_TABLE = "lesson_sessions";
    private static final String CLASSROOM_FK = "classroom_id";
    private static final String PERIOD_COLUMN = "period_name";

    @Override
    public String key() {
        return "classroomUsageProvider";
    }

    @Override
    public Object compute(SourceRecord rec, DerivedPropertyDef def, MappingContext ctx) {
        if (rec == null || def == null || def.name == null) {
            return 0L;
        }
        // 教室对象自身主键即 classroom_id 的取值域；rec.pk() 为空说明来源行异常，直接返回 0 而不抛异常。
        Object classroomId = rec.pk();
        if (classroomId == null) {
            return 0L;
        }
        if (SESSION_COUNT.equals(def.name)) {
            return ctx.lookups().count(SESSION_TABLE, CLASSROOM_FK, classroomId);
        }
        if (USED_PERIODS.equals(def.name)) {
            List<String> periods = ctx.lookups()
                    .joinKeys(SESSION_TABLE, CLASSROOM_FK, classroomId, PERIOD_COLUMN);
            // joinKeys 已做 DISTINCT，这里再过滤空节次，保证「占用节次数」不受脏数据影响。
            return periods.stream().filter(period -> period != null && !period.isBlank()).count();
        }
        // 未定义的派生属性名：返回 0 而不是抛错，避免单个类型配置笔误拖垮整批快照导入。
        return 0L;
    }
}
