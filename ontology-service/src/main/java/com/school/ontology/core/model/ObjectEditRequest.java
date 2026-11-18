package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 对象类型在线编辑的请求体（C 档，形状对齐 {@link RelationEditRequest}）。
 *
 * <p>字段全部为 public 且无业务逻辑：控制器用 Jackson 从 JSON 直接转成本对象，
 * 校验与编排全部落在 {@link ObjectTypeDefinitionEditor} 里，便于单测与复用。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ObjectEditRequest {

    /** 乐观版本：必须等于当前 ACTIVE 版本，否则 409 VERSION_CONFLICT。 */
    public String baseMappingVersion;
    /** 操作人；缺省「演示管理员」。 */
    public String operator;
    /** 变更说明；缺省按操作自动生成。 */
    public String note;
    /** 恢复基线需要二次确认。 */
    public boolean confirm;
    /** 修改后的对象类型定义（必须携带完整定义，不可变字段由后端逐项比对）。 */
    public ObjectTypeDef objectType;
}
