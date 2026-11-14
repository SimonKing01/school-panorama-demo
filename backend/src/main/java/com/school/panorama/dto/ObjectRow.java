package com.school.panorama.dto;

/** 全局列表 / 搜索结果的一行。 */
public class ObjectRow {

    public String type;
    public long id;
    public String code;
    public String name;
    public Long schoolId;
    public String schoolName;
    public Long classId;
    public String className;
    public String subject;
    public String extra;
    public LessonView currentLesson;
    public LessonView nextLesson;
}
