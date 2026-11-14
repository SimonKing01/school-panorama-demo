package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("teachers")
public class Teacher {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public String code;
    public String name;
    public Long schoolId;
    public String subject;
    public String title;
    public String phone;
    public Boolean substituteAvailable;
    public String status;
}
