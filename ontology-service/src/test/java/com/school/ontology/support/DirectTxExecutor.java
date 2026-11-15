package com.school.ontology.support;

import com.school.ontology.core.projection.TxExecutor;

import java.util.function.Supplier;

/** 单元测试事务执行器：直接执行工作单元，不依赖 Spring 事务管理器。 */
public class DirectTxExecutor implements TxExecutor {

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }
}
