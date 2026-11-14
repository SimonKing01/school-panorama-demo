package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalTime;

/** 课表初始快照，仅用于恢复演示数据与同步到今天。 */
@TableName("lesson_session_seed")
public class LessonSessionSeed {
    @TableId(value = "session_id", type = IdType.INPUT)
    public Long sessionId;
    public Long teacherId;
    public Long classroomId;
    public LocalDate lessonDate;
    public LocalTime startTime;
    public LocalTime endTime;
}
