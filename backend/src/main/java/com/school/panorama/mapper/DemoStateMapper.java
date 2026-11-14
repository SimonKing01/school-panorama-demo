package com.school.panorama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.school.panorama.entity.DemoState;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

public interface DemoStateMapper extends BaseMapper<DemoState> {

    @Update("""
            UPDATE demo_state SET demo_at = #{demoAt}, running = #{running},
              anchor_real_at = #{anchorRealAt}, updated_at = now()
            WHERE id = 1
            """)
    int updateState(Map<String, Object> p);
}
