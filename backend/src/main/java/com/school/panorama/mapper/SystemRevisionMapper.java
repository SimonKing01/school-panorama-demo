package com.school.panorama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.panorama.entity.SystemRevision;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SystemRevisionMapper extends BaseMapper<SystemRevision> {

    @Select("SELECT revision FROM system_revision WHERE id = 1")
    long revision();

    @Update("UPDATE system_revision SET revision = revision + 1, updated_at = now() WHERE id = 1")
    int bump();

    @Select("SELECT 1")
    int ping();
}
