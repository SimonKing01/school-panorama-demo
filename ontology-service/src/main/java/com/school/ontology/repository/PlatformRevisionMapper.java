package com.school.ontology.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 平台版本（单调只增）访问。沿用现有业务的「全注解式 Mapper」风格，
 * 与架构 §10.1 的 MyBatis Plus 依赖保持一致；其余投影写入因需要精确控制
 * ON CONFLICT 与 JSONB 绑定，统一走 JdbcTemplate。
 */
@Mapper
public interface PlatformRevisionMapper {

    @Select("SELECT revision FROM ont_platform_revision WHERE id = 1")
    long revision();

    @Update("UPDATE ont_platform_revision SET revision = revision + 1, updated_at = now() WHERE id = 1")
    int bump();
}
