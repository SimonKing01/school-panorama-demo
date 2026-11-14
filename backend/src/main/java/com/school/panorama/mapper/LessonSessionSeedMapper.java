package com.school.panorama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.panorama.entity.LessonSessionSeed;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

public interface LessonSessionSeedMapper extends BaseMapper<LessonSessionSeed> {

    @Select("SELECT min(lesson_date) FROM lesson_session_seed")
    LocalDate minLessonDate();

    @Update("UPDATE lesson_session_seed SET lesson_date = lesson_date + #{delta}")
    int shiftDates(@Param("delta") int delta);

    @Update("UPDATE lesson_session_seed SET lesson_date = CURRENT_DATE")
    int alignToday();
}
