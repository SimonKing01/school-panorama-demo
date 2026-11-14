package com.school.panorama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.panorama.dto.LessonView;
import com.school.panorama.entity.LessonSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface LessonSessionMapper extends BaseMapper<LessonSession> {

    /** 共享业务查询：一次 JOIN 取全量授课安排，所有视角复用，避免各视角拼业务事实。 */
    @Select("""
            SELECT ls.id AS "id", ls.code AS "code",
                   ls.school_id AS "schoolId", sch.name AS "schoolName",
                   ls.class_id AS "classId", cls.name AS "className",
                   ls.course_id AS "courseId", crs.name AS "courseName", crs.subject AS "subject",
                   ls.teacher_id AS "teacherId", tch.name AS "teacherName",
                   ls.classroom_id AS "classroomId", rom.name AS "classroomName",
                   to_char(ls.lesson_date, 'YYYY-MM-DD') AS "lessonDate",
                   to_char(ls.start_time, 'HH24:MI') AS "startTime",
                   to_char(ls.end_time, 'HH24:MI') AS "endTime",
                   ls.period_name AS "periodName",
                   (SELECT count(*) FROM students s WHERE s.class_id = ls.class_id) AS "studentCount"
            FROM lesson_sessions ls
            JOIN schools sch ON sch.id = ls.school_id
            JOIN classes cls ON cls.id = ls.class_id
            JOIN courses crs ON crs.id = ls.course_id
            JOIN teachers tch ON tch.id = ls.teacher_id
            JOIN classrooms rom ON rom.id = ls.classroom_id
            ORDER BY ls.lesson_date, ls.start_time, ls.school_id, ls.class_id, ls.id
            """)
    List<LessonView> selectAllLessonViews();

    @Select("SELECT to_char(max(end_time), 'HH24:MI') FROM lesson_sessions")
    String maxEndTime();

    /** 调课写入：更新一次授课安排并递增 version。 */
    @Update("""
            UPDATE lesson_sessions SET
              teacher_id = #{teacherId}, classroom_id = #{classroomId},
              lesson_date = #{lessonDate}::date,
              start_time = #{startTime}::time, end_time = #{endTime}::time,
              version = version + 1, updated_at = now()
            WHERE id = #{id}
            """)
    int applyAdjust(Map<String, Object> p);

    /** 同步到今天：按天数差整体平移授课安排日期。 */
    @Update("UPDATE lesson_sessions SET lesson_date = lesson_date + #{delta}")
    int shiftDates(@Param("delta") int delta);

    /** 恢复演示数据：从 seed 恢复教师/教室/起止时间，日期对齐今天，重置 version。 */
    @Update("""
            UPDATE lesson_sessions ls SET
              teacher_id = s.teacher_id, classroom_id = s.classroom_id,
              lesson_date = CURRENT_DATE,
              start_time = s.start_time, end_time = s.end_time,
              version = 1, updated_at = now()
            FROM lesson_session_seed s
            WHERE ls.id = s.session_id
            """)
    int resetFromSeed();

    /** 事务级 advisory lock，串行化并发调课，避免漏判冲突。 */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(918273)) t")
    int advisoryLock();
}
