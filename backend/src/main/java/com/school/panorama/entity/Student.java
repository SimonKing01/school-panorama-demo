package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;

@TableName("students")
public class Student {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public String code;
    public String name;
    public Long classId;
    public String gender;
    public String studentNo;
    public String guardianPhone;
    public LocalDate enrollDate;
}
