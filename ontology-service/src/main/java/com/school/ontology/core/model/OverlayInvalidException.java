package com.school.ontology.core.model;

/**
 * overlay 定义文件非法（解析失败或校验不通过）。
 *
 * <p>只在 {@link ModelDefinitionLoader} 构造期内部使用：捕获它意味着「回退打包基线」，
 * 与「打包基线非法 → 拒绝启动」是两条完全不同的闸门（07 增量设计 §5.3）。</p>
 */
public class OverlayInvalidException extends RuntimeException {

    public OverlayInvalidException(String message) {
        super(message);
    }
}
