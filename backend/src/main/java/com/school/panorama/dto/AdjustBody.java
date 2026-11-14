package com.school.panorama.dto;

/** preview 与 adjust 共用的调整请求体。 */
public class AdjustBody {

    public Long teacherId;
    public Long classroomId;
    public String lessonDate;
    public String startTime;
    public String endTime;
    public String reason;
}
