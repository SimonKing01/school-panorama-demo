package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

@TableName("courses")
public class Course {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public String code;
    public String name;
    public String subject;
    public BigDecimal credit;
    public String category;
}
