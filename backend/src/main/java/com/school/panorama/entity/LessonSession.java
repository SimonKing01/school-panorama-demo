package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 全系统唯一课表事实来源。 */
@TableName("lesson_sessions")
public class LessonSession {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public String code;
    public Long schoolId;
    public Long classId;
    public Long courseId;
    public Long teacherId;
    public Long classroomId;
    public LocalDate lessonDate;
    public LocalTime startTime;
    public LocalTime endTime;
    public String periodName;
    public String status;
    public Integer version;
    public LocalDateTime updatedAt;
}
