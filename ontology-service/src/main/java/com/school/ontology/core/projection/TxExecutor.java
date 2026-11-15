package com.school.ontology.core.projection;

import java.util.function.Supplier;

/**
 * 事务边界抽象：生产实现落在本体库单事务；单元测试可用「直接执行」实现，
 * 从而在不启动数据库的情况下验证去重、乱序、重建等语义。
 */
public interface TxExecutor {

    <T> T inTransaction(Supplier<T> work);
}
