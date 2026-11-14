package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 统一演示时钟：固定或随真实时间推进。 */
@TableName("demo_state")
public class DemoState {
    @TableId(value = "id", type = IdType.INPUT)
    public Integer id;
    public LocalDateTime demoAt;
    public Boolean running;
    public LocalDateTime anchorRealAt;
    public LocalDateTime updatedAt;
}
