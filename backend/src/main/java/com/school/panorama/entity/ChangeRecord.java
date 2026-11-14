package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("change_records")
public class ChangeRecord {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public Long sessionId;
    public String sessionCode;
    public Long schoolId;
    public String changeType;
    public String reason;
    public String operator;
    public LocalDateTime occurredAt;
    public String beforeJson;
    public String afterJson;
    public String diffJson;
    public String affectedJson;
    public Integer affectedStudentCount;
    public Boolean isSeed;
}
