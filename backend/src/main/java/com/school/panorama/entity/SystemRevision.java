package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 全局数据版本号，供短轮询感知变化。 */
@TableName("system_revision")
public class SystemRevision {
    @TableId(value = "id", type = IdType.INPUT)
    public Integer id;
    public Long revision;
    public LocalDateTime updatedAt;
}
