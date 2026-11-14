package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("classrooms")
public class Classroom {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public String code;
    public String name;
    public Long schoolId;
    public String building;
    public Integer capacity;
    public String roomType;
}
