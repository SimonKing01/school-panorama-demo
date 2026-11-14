package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("schools")
public class School {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public String code;
    public String name;
    public String address;
    public String phone;
    public String slogan;
    public LocalDateTime createdAt;
}
