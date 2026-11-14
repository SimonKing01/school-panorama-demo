package com.school.panorama.dto;

/**
 * 一次具体授课安排，所有业务视角共用的唯一事实对象。
 * 字段由共享 JOIN 查询（带引号驼峰别名）自动映射，status 由 BusinessQueryService 按演示时间计算。
 */
public class LessonView {

    public long id;
    public String code;
    public long schoolId;
    public String schoolName;
    public long classId;
    public String className;
    public long courseId;
    public String courseName;
    public String subject;
    public long teacherId;
    public String teacherName;
    public long classroomId;
    public String classroomName;
    public String lessonDate;
    public String startTime;
    public String endTime;
    public String periodName;
    public long studentCount;
    public String status;
}
