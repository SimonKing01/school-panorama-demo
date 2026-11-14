package com.school.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** classes 是 SQL 保留字附近命名的班级表，实体名用 Clazz 避免与 java.lang.Class 混淆。 */
@TableName("classes")
public class Clazz {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public String code;
    public String name;
    public Long schoolId;
    public String grade;
    public Long headTeacherId;
    public Long homeRoomId;
}
