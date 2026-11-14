package com.school.panorama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.panorama.entity.ChangeRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface ChangeRecordMapper extends BaseMapper<ChangeRecord> {

    String CHANGE_COLS = """
            id, session_id, session_code, school_id, change_type, reason, operator,
            to_char(occurred_at, 'YYYY-MM-DD"T"HH24:MI:SS') AS occurred_at,
            before_json::text AS before_json, after_json::text AS after_json,
            diff_json::text AS diff_json, affected_json::text AS affected_json,
            affected_student_count, is_seed
            """;

    @Select("SELECT " + CHANGE_COLS + """
            FROM change_records
            WHERE (#{schoolId}::bigint IS NULL OR school_id = #{schoolId}::bigint)
            ORDER BY occurred_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> listChanges(@Param("schoolId") Long schoolId, @Param("limit") int limit);

    @Select("SELECT count(*) FROM change_records WHERE (#{schoolId}::bigint IS NULL OR school_id = #{schoolId}::bigint)")
    long countChanges(@Param("schoolId") Long schoolId);

    /** 取当前最大主键，供写入后立即回读详情（事务内可见本事务插入）。 */
    @Select("SELECT coalesce(max(id), 0) FROM change_records")
    long maxId();

    @Select("SELECT " + CHANGE_COLS + " FROM change_records WHERE id = #{id}")
    Map<String, Object> getChange(@Param("id") long id);

    /** 写入一条调课变更记录（快照为 JSON 字符串，由 Service 序列化）。 */
    @Insert("""
            INSERT INTO change_records
             (session_id, session_code, school_id, change_type, reason, operator, occurred_at,
              before_json, after_json, diff_json, affected_json, affected_student_count, is_seed)
            VALUES
             (#{sessionId}, #{sessionCode}, #{schoolId}, #{changeType}, #{reason}, #{operator},
              #{occurredAt}::timestamp, #{beforeJson}::jsonb, #{afterJson}::jsonb,
              #{diffJson}::jsonb, #{affectedJson}::jsonb, #{affectedStudentCount}, FALSE)
            """)
    int insertChange(Map<String, Object> p);

    @Delete("DELETE FROM change_records WHERE is_seed = FALSE")
    int deleteNonSeed();

    /** 恢复演示数据：预置记录时间归位到当天 08:20/09:05/09:40，并刷新快照里的日期。 */
    @Update("""
            UPDATE change_records SET
              occurred_at = CURRENT_DATE::timestamp + CASE id
                    WHEN 1 THEN INTERVAL '8 hours 20 minutes'
                    WHEN 2 THEN INTERVAL '9 hours 5 minutes'
                    WHEN 3 THEN INTERVAL '9 hours 40 minutes'
                    ELSE INTERVAL '8 hours 20 minutes' END,
              before_json = jsonb_set(before_json, '{lessonDate}', to_jsonb(CURRENT_DATE::text), true),
              after_json = jsonb_set(after_json, '{lessonDate}', to_jsonb(CURRENT_DATE::text), true)
            WHERE is_seed = TRUE
            """)
    int resetSeedTimes();

    /** 同步到今天：变更记录时间按天数差平移。 */
    @Update("UPDATE change_records SET occurred_at = occurred_at + make_interval(days => #{delta})")
    int shiftTimes(@Param("delta") int delta);

    /** 同步到今天：修改前后快照里的授课日期也要跟着平移，否则变更详情会显示旧日期。 */
    @Update("""
            UPDATE change_records SET
              before_json = jsonb_set(before_json, '{lessonDate}', to_jsonb(#{newDate}::text), false),
              after_json  = jsonb_set(after_json,  '{lessonDate}', to_jsonb(#{newDate}::text), false)
            WHERE before_json -> 'lessonDate' IS NOT NULL
            """)
    int shiftJsonbLessonDate(@Param("newDate") String newDate);
}
