package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 关系类型在线编辑的请求体（07 增量设计 §6 的公共入参）。
 *
 * <p>字段全部为 public 且无业务逻辑：控制器用 Jackson 从 JSON 直接转成本对象，
 * 校验与编排全部落在 {@link RelationTypeDefinitionEditor} 里，便于单测与复用。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelationEditRequest {

    /** 乐观版本：必须等于当前 ACTIVE 版本，否则 409 VERSION_CONFLICT。 */
    public String baseMappingVersion;
    /** 操作人；缺省「演示管理员」（与原业务 change_records.operator 口径一致）。 */
    public String operator;
    /** 变更说明；缺省按操作自动生成。 */
    public String note;
    /** 删除被映射引用的关系时需要显式二次确认。 */
    public boolean force;
    /** 恢复基线需要二次确认。 */
    public boolean confirm;
    /** 新增 / 修改时的关系类型定义。 */
    public RelationTypeDef relation;
}
