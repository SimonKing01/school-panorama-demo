package com.school.panorama.dto;

/** POST /api/demo/clock 可选字段；demoTime 优先于 minuteOffset。 */
public class ClockRequest {

    public String demoTime;
    public Boolean running;
    public Integer minuteOffset;
}
